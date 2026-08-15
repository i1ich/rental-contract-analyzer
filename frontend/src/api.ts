import type { AnalysisResult } from './types'

// Set at build/dev time (e.g. .env.local: VITE_API_BASE_URL=https://<api-id>.execute-api.sa-east-1.amazonaws.com/prod)
// once the API is deployed (T9/T16). Empty string keeps requests same-origin, which will 404
// against the Vite dev server until it's configured — see the README dev instructions.
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

// Mirrors GenerateUploadUrlHandler.MAX_UPLOAD_SIZE_BYTES (functions/generate-upload-url) — kept
// in sync manually since the two can't share a constant across the JS/Java boundary.
export const MAX_UPLOAD_SIZE_BYTES = 10 * 1024 * 1024

export class ApiError extends Error {}

interface UploadUrlResponse {
  uploadUrl: string
  objectKey: string
  requiredContentType: string
  expiresInSeconds: number
}

export async function requestUploadUrl(fileSizeBytes: number): Promise<UploadUrlResponse> {
  const res = await fetch(`${API_BASE_URL}/upload-url`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileSizeBytes }),
  })
  if (!res.ok) {
    throw new ApiError((await safeErrorMessage(res)) ?? 'No pudimos preparar la subida del archivo.')
  }
  return res.json() as Promise<UploadUrlResponse>
}

export async function uploadFile(uploadUrl: string, requiredContentType: string, file: File): Promise<void> {
  const res = await fetch(uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': requiredContentType },
    body: file,
  })
  if (!res.ok) {
    throw new ApiError('No pudimos subir el archivo. Probá de nuevo.')
  }
}

export async function analyzeContract(objectKey: string): Promise<AnalysisResult> {
  const res = await fetch(`${API_BASE_URL}/analyze`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ objectKey }),
  })
  if (!res.ok) {
    throw new ApiError((await safeErrorMessage(res)) ?? 'No pudimos analizar el contrato.')
  }
  return res.json() as Promise<AnalysisResult>
}

async function safeErrorMessage(res: Response): Promise<string | null> {
  try {
    const body: unknown = await res.json()
    if (body && typeof body === 'object' && 'error' in body && typeof body.error === 'string') {
      return body.error
    }
    return null
  } catch {
    return null
  }
}
