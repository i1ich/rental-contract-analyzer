/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Deployed API Gateway base URL (e.g. https://<api-id>.execute-api.sa-east-1.amazonaws.com/prod). Unset until T16. */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
