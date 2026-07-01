# LeaseLens (rental-contract-analyzer)

**Upload a rental contract (PDF) → get a plain-language analysis with severity-ranked red flags, each backed by the exact clause quote and its location in the document.** Montevideo, Uruguay-first.

> Status: early build. See `mvp3_contract_analyzer_plan.md` (private) for the full task plan.

## Architecture

Serverless, mirrors the `photolist-latam` template: Java 21 Lambdas + AWS CDK (Java) for infrastructure, React/TS/Vite PWA frontend on S3 + CloudFront.

- `functions/` — Java 21 Lambda modules (Maven)
- `infrastructure/` — AWS CDK app (Java), one stack per concern (Storage / Api / Frontend)
- `frontend/` — React + TypeScript + Vite PWA, Spanish UI
- `docs/` — deployment and architecture notes
- `scripts/` — deploy/ops scripts

## Locked scope (MVP)

- Jurisdiction: Montevideo, Uruguay only
- Document type: residential rental contracts only
- Input: text-layer PDF (OCR fallback for scanned PDFs is a later phase)
- LLM: Claude, config in SSM
- No auth/accounts — anonymous, ephemeral analysis
- Mandatory disclaimer: "herramienta educativa, no es asesoramiento legal"

## Development

```sh
./mvnw -version                 # verify Maven wrapper
cd infrastructure && ../mvnw package   # build CDK app
cd frontend && npm install && npm run build
```

## License

MIT — see [LICENSE](LICENSE).
