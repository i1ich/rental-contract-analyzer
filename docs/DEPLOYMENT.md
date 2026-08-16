# Deployment

Status as of T11: all three stacks (`LeaseLensStorageStack`, `LeaseLensApiStack`,
`LeaseLensFrontendStack`) are deployed and live in `sa-east-1`. This doc covers the deploy
recipe end to end.

## Prerequisites

- AWS account + credentials configured (`aws configure` or an SSO profile), same as `photolist-latam`.
- JDK 21 available and used for `java`/Maven — this machine has JDK 8 as the default `java` on
  `PATH` and JDK 21 installed separately (`C:\Users\<you>\.jdks\openjdk-21.0.2` or wherever it
  lives). **Setting `JAVA_HOME` alone is not enough for `cdk`:** `cdk.json`'s app command is
  `java -jar target/leaselens-infrastructure.jar`, which resolves `java` from `PATH` and ignores
  `JAVA_HOME` entirely, so the CDK app still fails to load with `UnsupportedClassVersionError`
  (`class file version 65.0`). Prepend JDK 21's `bin` to `PATH` for the `cdk` commands —
  `$env:PATH = "C:\Users\<you>\.jdks\openjdk-21.0.2\bin;" + $env:PATH` in PowerShell. `JAVA_HOME`
  is still what Maven needs.
- Node.js + `npx` (used to run the AWS CDK CLI: `npx aws-cdk@2.130.0 <command>`, matching the
  `aws-cdk-lib` version pinned in `infrastructure/pom.xml`).

## Build order

The CDK app loads Lambda code from prebuilt shaded jars (`Code.fromAsset(...)`), so Lambda
modules must be packaged *before* `cdk synth`/`cdk deploy`:

```sh
./mvnw -f functions/generate-upload-url/pom.xml package
./mvnw -f functions/extract-text/pom.xml package
./mvnw -f functions/analyze-contract/pom.xml package
./mvnw -f infrastructure/pom.xml package
```

## SSM parameters (create these manually before first deploy — never in CDK/CloudFormation)

| Parameter | Type | Purpose |
|---|---|---|
| `/leaselens/openrouter-api-key` | `SecureString` | OpenRouter API key, read at Lambda cold start (shared by both Lambdas below) |
| `/leaselens/openrouter-model` | `String` | OpenRouter model id for contract analysis (`analyze-contract`) — defaults to `anthropic/claude-sonnet-5` if unset, the only model this project's T12 golden-set gate has actually passed on. Avoid `nvidia/nemotron-3-ultra-550b-a55b:free` here despite being free: it takes ~85s on a real contract, past API Gateway's hard 29s timeout. |
| `/leaselens/openrouter-vision-model` | `String` | OpenRouter model id for scanned-contract OCR transcription (`extract-text`, T6) — defaults to `google/gemini-2.5-flash-lite` if unset. Independent from the analysis model above since transcription and structured-JSON analysis have different cost/quality tradeoffs. |

```sh
aws ssm put-parameter --name /leaselens/openrouter-api-key --type SecureString --value "sk-or-v1-..."
aws ssm put-parameter --name /leaselens/openrouter-model --type String --value "anthropic/claude-sonnet-5"
aws ssm put-parameter --name /leaselens/openrouter-vision-model --type String --value "google/gemini-2.5-flash-lite"
```

LLM calls go through [OpenRouter](https://openrouter.ai) rather than any provider's API directly —
one key, and each step's model is just a config string, swappable via the params above without a
redeploy. This includes T6's OCR fallback: scanned/photographed contracts are transcribed by a
vision-capable model via OpenRouter, **not** AWS Textract — Textract has no regional endpoint in
`sa-east-1`, where this stack lives (confirmed live during the first real deploy: DNS resolution
for `textract.sa-east-1.amazonaws.com` fails outright). Rendering pages locally with PDFBox and
transcribing them via the same OpenRouter integration already used for analysis sidesteps the
region problem entirely instead of requiring a cross-region workaround.

## Deploy

```sh
cd infrastructure
JAVA_HOME=/path/to/jdk21 npx aws-cdk@2.130.0 deploy LeaseLensStorageStack
JAVA_HOME=/path/to/jdk21 npx aws-cdk@2.130.0 deploy LeaseLensApiStack
JAVA_HOME=/path/to/jdk21 npx aws-cdk@2.130.0 deploy LeaseLensFrontendStack
```

### Frontend (T11): build + sync before/after deploying the stack

`LeaseLensFrontendStack` only creates the S3 bucket + CloudFront distribution — it does **not**
upload the built site. CDK's `BucketDeployment` construct is deliberately not used: on this
project's pinned CDK version (2.130.0), its bundled AWS-CLI Lambda layer fails every time with
`TypeError: unsupported operand type(s) for |: 'type' and 'type'` (a Python 3.9-runtime-vs-newer-
botocore version-skew bug in that old CDK release's shared layer, confirmed live via the custom
resource's CloudWatch logs — not something fixable in our own code short of upgrading CDK).
Sync manually instead, after both `npm run build` and the CDK deploy:

```sh
cd frontend
VITE_API_BASE_URL=https://<api-id>.execute-api.sa-east-1.amazonaws.com/prod npm run build
aws s3 sync dist/ s3://<FrontendBucketName from stack output> --delete
aws cloudfront create-invalidation --distribution-id <FrontendDistributionId from stack output> --paths "/*"
```

The stack outputs `FrontendBucketName`, `FrontendDistributionId`, and `FrontendUrl` — re-run the
sync + invalidation any time the frontend changes; the CDK deploy itself only needs re-running if
the bucket/distribution configuration changes.

## Known issues

- **Cache-miss analyses time out (blocking).** `anthropic/claude-sonnet-5` takes ~83-92s on a real
  contract — measured live 2026-08-16 via the Lambda's own CloudWatch `REPORT` lines — while API
  Gateway's REST integration timeout is hard-capped at 29s and is not configurable. So a first
  analysis of any contract returns **504** even though the Lambda finishes and caches the result;
  a repeat request then returns the cached analysis in well under a second. Extraction is not the
  bottleneck (1.3s text-layer, 9.1s vision-OCR) — the analysis call is, since it generates 5-8K
  completion tokens. Fixing it means either a faster model (`claude-haiku-4.5` is the untested
  candidate) or making `/analyze` asynchronous.
- **The service worker serves the previous build after a deploy.** After `aws s3 sync` and a
  *completed* CloudFront invalidation, returning visitors still load the old JS bundle from the
  PWA precache; `registerType: 'autoUpdate'` picks up the new build one reload later. Bypass it
  when verifying a deploy (unregister the service worker and clear caches), and don't treat "the
  live page still looks old" as a failed sync without checking `index.html` at the origin first.
- **`generate-upload-url`'s size check is soft.** It's a client-declared `fileSizeBytes` (see its
  Javadoc) — a presigned PUT can't enforce an upload's actual byte count the way a presigned POST
  policy can. Hard enforcement lives downstream in `ExtractTextHandler.MAX_OBJECT_SIZE_BYTES`,
  which rejects on the object's real `Content-Length` before any parsing or LLM cost (T13).
- **Vision-OCR transcription is not fully deterministic** run-to-run on the same scan. `OcrTextCache`
  keys transcribed text by the S3 object's ETag (48h TTL) so a retry of the same upload reuses its
  transcription and `analyze-contract`'s content-hash cache can hit — confirmed live: 9,101ms on
  the first OCR pass, 112ms on a retry.

## Smoke test (once deployed)

With T4 (`POST /upload-url`) in place, the full flow is upload → analyze, no manual `aws s3 cp`
needed:

```sh
# 1. Get a presigned PUT URL + object key.
curl -X POST https://<api-id>.execute-api.sa-east-1.amazonaws.com/prod/upload-url \
  -H 'Content-Type: application/json' \
  -d '{"fileSizeBytes": 123456}'
# => {"uploadUrl": "...", "objectKey": "uploads/<uuid>.pdf", "requiredContentType": "application/pdf", "expiresInSeconds": 900}

# 2. PUT the PDF to that URL — Content-Type MUST match requiredContentType exactly, or S3
#    rejects the signature (403).
curl -X PUT "<uploadUrl from step 1>" \
  -H 'Content-Type: application/pdf' \
  --data-binary @sample-contract.pdf

# 3. Analyze it.
curl -X POST https://<api-id>.execute-api.sa-east-1.amazonaws.com/prod/analyze \
  -H 'Content-Type: application/json' \
  -d '{"objectKey": "<objectKey from step 1>"}'
```

Expect a JSON body with `summary` and `findings[]` — **but not on the first call**: see "Known
issues" above. A cache-miss analysis currently exceeds API Gateway's 29s cap and returns 504 for
text-layer and scanned contracts alike, while the Lambda keeps running and caches the result; call
step 3 again and the cached analysis comes back in well under a second (`cachedAt` set). Measured
2026-08-16: text-layer 504 → 504 → 200 in 719ms (13 findings, 12/12 quotes verbatim); scanned
504 → 504 → 200 in 612ms (10 findings).

If the PDF has no native text layer,
`analyze-contract` now transparently falls back to vision-model OCR (T6, via OpenRouter — not
AWS Textract, which isn't available in this region) before deciding whether there's enough usable
text — a `422` at that point means OCR itself came up empty (e.g. a low-quality scan), not simply
"scanned PDFs aren't supported". Note that a scanned contract's first request can take 15-30s
(OCR + analysis back to back) and may hit API Gateway's 29s hard timeout even though the Lambda
succeeds moments later — retry once if you see a 504 on a scan.

The old manual-upload path (`aws s3 cp contract.pdf s3://<bucket>/manual-test/contract.pdf`,
then passing that key straight to `/analyze`) still works too, e.g. for a quick test that skips
presigning.
