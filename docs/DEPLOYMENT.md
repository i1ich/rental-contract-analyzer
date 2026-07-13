# Deployment

Status as of T9: infrastructure code exists and synthesizes locally (`cdk synth`); nothing has
been deployed to AWS yet. This doc covers what deploying will look like once we decide to ship.

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

- **`POST /upload-url`** — the presigned-S3-PUT Lambda (T4) hasn't been built. Until it lands,
  getting a PDF into the uploads bucket for a smoke test means putting it there manually
  (`aws s3 cp contract.pdf s3://<ContractUploadsBucket name>/manual-test/contract.pdf`) and
  passing that key as `objectKey` to `POST /analyze`.
- **Frontend hosting (T11)** — no CloudFront/S3 static site stack yet.
- **OCR fallback (T6)** — scanned/photographed PDFs currently return a clean `422` from
  `analyze-contract` instead of being processed.
- **CI/CD (T15)** — no GitHub Actions workflow yet; builds are local only.

## Smoke test (once deployed)

```sh
aws s3 cp sample-contract.pdf s3://<bucket>/manual-test/sample-contract.pdf
curl -X POST https://<api-id>.execute-api.sa-east-1.amazonaws.com/prod/analyze \
  -H 'Content-Type: application/json' \
  -d '{"objectKey": "manual-test/sample-contract.pdf"}'
```

Expect a JSON body with `summary` and `findings[]` (or a `422` with a friendly Spanish message
if the PDF has no usable text layer).
