# Deployment

Status as of T4/T6/T10: infrastructure code exists and synthesizes locally (`cdk synth`);
nothing has been deployed to AWS yet. This doc covers what deploying will look like once we
decide to ship.

## Prerequisites

- AWS account + credentials configured (`aws configure` or an SSO profile), same as `photolist-latam`.
- JDK 21 available and used for `java`/Maven — this machine has JDK 8 as the default `java` on
  `PATH` and JDK 21 installed separately; point `JAVA_HOME` at the JDK 21 install
  (`C:\Users\<you>\.jdks\openjdk-21.0.2` or wherever it lives) before running `cdk` commands,
  otherwise the CDK app fails to load with `UnsupportedClassVersionError`.
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
| `/leaselens/openrouter-model` | `String` | OpenRouter model id for contract analysis (`analyze-contract`) — defaults to `nvidia/nemotron-3-ultra-550b-a55b:free` if unset |
| `/leaselens/openrouter-vision-model` | `String` | OpenRouter model id for scanned-contract OCR transcription (`extract-text`, T6) — defaults to `google/gemini-2.5-flash-lite` if unset. Independent from the analysis model above since transcription and structured-JSON analysis have different cost/quality tradeoffs. |

```sh
aws ssm put-parameter --name /leaselens/openrouter-api-key --type SecureString --value "sk-or-v1-..."
aws ssm put-parameter --name /leaselens/openrouter-model --type String --value "nvidia/nemotron-3-ultra-550b-a55b:free"
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
```

## What's NOT wired yet

- **Frontend hosting (T11)** — no CloudFront/S3 static site stack yet. The PWA (T10) has to be
  run locally (`cd frontend && npm run dev`) pointed at the deployed API via `VITE_API_BASE_URL`
  until T11 lands.
- **Cost/abuse/privacy guards (T13)** — no API Gateway throttling; `generate-upload-url`'s max
  file size check is a client-declared `fileSizeBytes` soft check only (see its Javadoc) — a
  presigned PUT can't enforce an upload's actual byte count the way a presigned POST policy can.
  The vision-OCR call's per-run cost isn't budget-capped. It's also not fully deterministic
  run-to-run on the same scanned document (confirmed live), which means `analyze-contract`'s
  content-hash cache rarely hits for scanned contracts specifically — every request tends to
  re-run the full OCR+analysis chain, worth a guard here.
- **CI/CD (T15)** — no GitHub Actions workflow yet; builds are local only.

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

Expect a JSON body with `summary` and `findings[]`. If the PDF has no native text layer,
`analyze-contract` now transparently falls back to vision-model OCR (T6, via OpenRouter — not
AWS Textract, which isn't available in this region) before deciding whether there's enough usable
text — a `422` at that point means OCR itself came up empty (e.g. a low-quality scan), not simply
"scanned PDFs aren't supported". Note that a scanned contract's first request can take 15-30s
(OCR + analysis back to back) and may hit API Gateway's 29s hard timeout even though the Lambda
succeeds moments later — retry once if you see a 504 on a scan.

The old manual-upload path (`aws s3 cp contract.pdf s3://<bucket>/manual-test/contract.pdf`,
then passing that key straight to `/analyze`) still works too, e.g. for a quick test that skips
presigning.
