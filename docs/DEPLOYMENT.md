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
| `/leaselens/openrouter-api-key` | `SecureString` | OpenRouter API key, read at Lambda cold start |
| `/leaselens/openrouter-model` | `String` | OpenRouter model id (defaults to `nvidia/nemotron-3-ultra-550b-a55b:free` if unset) |

```sh
aws ssm put-parameter --name /leaselens/openrouter-api-key --type SecureString --value "sk-or-v1-..."
aws ssm put-parameter --name /leaselens/openrouter-model --type String --value "nvidia/nemotron-3-ultra-550b-a55b:free"
```

LLM calls go through [OpenRouter](https://openrouter.ai) rather than the Anthropic API directly —
one key, and the model is just a config string (`nvidia/nemotron-3-ultra-550b-a55b:free`,
`anthropic/claude-sonnet-5`, ...), swappable via the `openrouter-model` param above
without a redeploy.

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
  Textract's per-run cost isn't budget-capped, only implicitly bounded by the polling timeout.
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
`analyze-contract` now transparently falls back to Textract OCR (T6) before deciding whether
there's enough usable text — a `422` at that point means OCR itself came up empty (e.g. a
low-quality scan), not simply "scanned PDFs aren't supported".

The old manual-upload path (`aws s3 cp contract.pdf s3://<bucket>/manual-test/contract.pdf`,
then passing that key straight to `/analyze`) still works too, e.g. for a quick test that skips
presigning.
