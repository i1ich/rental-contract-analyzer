# LeaseLens (rental-contract-analyzer)

**Upload a residential rental contract (PDF) → get a plain-language analysis with severity-ranked
red flags, each backed by the exact clause quote and its location in the document.** Montevideo,
Uruguay-first, Spanish UI.

> **Status: deployed and running.** All three CDK stacks are live in `sa-east-1` and the full
> upload → OCR → analyze → poll flow has been verified end to end against real contracts.
> **A public launch is deliberately on hold** pending a legal review of the product's framing —
> a tool that reads legal contracts should not ship on a disclaimer alone. No public URL is
> published for that reason; the deployment is private. Engineering is complete, and nothing
> about it decays while the review is pending.

## What it does

1. The browser asks the API for a presigned `PUT` URL and uploads the PDF straight to S3 — the
   contract never passes through a Lambda on the way in.
2. `extract-text` pulls the native text layer with **PDFBox**. If the PDF is a scan or a photo
   with no usable text layer, it renders the pages and transcribes them with a **vision model via
   OpenRouter** — deliberately *not* AWS Textract, which has no regional endpoint in `sa-east-1`
   where this stack lives. Transcriptions are cached by S3 ETag (48h TTL), so a retry of the same
   upload skips the OCR cost entirely.
3. `analyze-contract` sends the contract text to an LLM (Claude by default, through OpenRouter)
   and returns a summary plus findings, each one carrying a `red` / `yellow` / `green` severity, a
   **verbatim** clause quote, its location in the document, and a plain-language explanation.
4. Results are cached by content hash, so re-analyzing the same contract is near-instant.

Analysis is **asynchronous by necessity**: a real analysis takes ~80–92s on the model that passes
the quality gate, while API Gateway caps a REST integration at a hard, unconfigurable 29s. `POST
/analyze` returns `202` with a job id in ~300ms and the client polls `GET /analyze/{jobId}`.

## Architecture

Serverless, mirroring the `photolist-latam` template: Java 21 Lambdas + AWS CDK (Java) for
infrastructure, React/TS/Vite PWA on S3 + CloudFront.

| Stack | Contents |
|---|---|
| `LeaseLensStorageStack` | Upload bucket (S3-managed encryption, **24h lifecycle expiry**, CORS) · `Analyses` DynamoDB table keyed by `contentHash`, items expire via `ttl` |
| `LeaseLensApiStack` | REST API (`POST /upload-url`, `POST /analyze`, `GET /analyze/{jobId}`, throttled 5 rps / burst 10) · four Java 21 Lambdas: `GenerateUploadUrlFn`, `ExtractTextFn`, `AnalyzeContractFn`, `AnalyzeWorkerFn` |
| `LeaseLensFrontendStack` | Private S3 site bucket + CloudFront (OAI). Site content is synced manually — CDK's `BucketDeployment` is deliberately unused (see `docs/DEPLOYMENT.md`) |

```
functions/        Java 21 Lambda modules (Maven)
  generate-upload-url/  presigned PUT + declared-size guard
  extract-text/         PDFBox text layer, vision-OCR fallback, ETag-keyed OCR cache
  analyze-contract/     async API handler + worker, OpenRouter analysis, content-hash cache
infrastructure/   AWS CDK app (Java), one stack per concern
frontend/         React + TypeScript + Vite PWA, Spanish UI
docs/             DEPLOYMENT.md — deploy recipe, SSM params, smoke test, known issues
```

Both LLM steps go through [OpenRouter](https://openrouter.ai): one key, and each model is a config
string in SSM (`/leaselens/openrouter-model`, `/leaselens/openrouter-vision-model`), swappable
without a redeploy.

## Locked scope (MVP)

- **Jurisdiction:** Montevideo, Uruguay only
- **Document type:** residential rental contracts only
- **Input:** text-layer PDF **or** scanned/photographed PDF (vision-model OCR fallback), ≤ 10 MB,
  ≤ 20 pages for the OCR path
- **No auth, no accounts** — anonymous, ephemeral analysis; uploads are deleted within 24h
- Explicit **cross-border data transfer consent** gate before upload, on the basis of Uruguay's
  Ley 18.331 art. 23 (the LLM call leaves the country); copy is draft pending the legal review
- Mandatory disclaimer: *"herramienta educativa, no es asesoramiento legal"*

## Quality gate

`GoldenSetValidationGateTest` runs every contract in a private, human-annotated golden set through
the real analysis service and enforces two ship criteria: **≥ 80% recall** of annotated findings
and **zero hallucinated quotes** — every `clauseQuote` must be a verbatim substring of the contract
text (modulo whitespace, case and accents). It is opt-in (`GOLDEN_SET_DIR`) because it costs money
and needs secrets, and the golden set never lives in this repo — it is real contract data.

Measured live on a cache-miss text-layer contract: **14 findings, 14/14 quotes verbatim**, settled
in 81.7s; cache hits settle in 0.5–2.5s; OCR ~9.1s cold, 112ms on a cached retry.

## Development

```sh
./mvnw -f functions/generate-upload-url/pom.xml package
./mvnw -f functions/extract-text/pom.xml package
./mvnw -f functions/analyze-contract/pom.xml package
./mvnw -f infrastructure/pom.xml package    # build the CDK app
cd frontend && npm install && npm run build
```

Lambda modules must be packaged **before** `cdk synth`/`cdk deploy` — the CDK app loads prebuilt
shaded jars via `Code.fromAsset(...)`. CI (GitHub Actions) builds and unit-tests all four Maven
modules plus the frontend on every push; the golden-set gate is excluded there by design.

The frontend dev server (`cd frontend && npm run dev`) needs `VITE_API_BASE_URL` pointed at a
deployed API to actually upload and analyze — see `frontend/.env.example`. Without it the upload
flow fails cleanly with a Spanish error message, which is a convenient way to exercise the UI's
error state.

Full deploy recipe, required SSM parameters, the curl smoke test and the known issues live in
[`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

## License

MIT — see [LICENSE](LICENSE).
