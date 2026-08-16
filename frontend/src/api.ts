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

// Analysis is asynchronous: POST /analyze only accepts the job and answers immediately with a
// jobId, and the result is collected by polling GET /analyze/{jobId}. That is not a stylistic
// choice — API Gateway caps a REST integration at a hard, unconfigurable 29s while a real analysis
// takes ~83-92s, so the synchronous version of this call returned 504 on every cache miss.

// Poll spacing: fast enough that a cache hit (which the worker resolves in well under a second)
// still feels instant, slow enough that a full ~90s analysis costs ~45 requests rather than
// hundreds — and API Gateway is throttled at 5 req/s across all users (T13).
const POLL_INTERVAL_MS = 2000

// Ceiling on how long we wait before giving up. Sized off the worker's own 300s Lambda timeout
// plus headroom: past this, the job is not slow, it is gone.
const POLL_TIMEOUT_MS = 5 * 60 * 1000

interface StartAnalysisResponse {
  jobId: string
  status: string
}

type AnalysisJobStatus =
  | { status: 'pending' }
  | { status: 'done'; result: AnalysisResult }
  | { status: 'error'; error: string; errorStatusCode: number }

export async function startAnalysis(objectKey: string): Promise<string> {
  const res = await fetch(`${API_BASE_URL}/analyze`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ objectKey }),
  })
  if (!res.ok) {
    throw new ApiError((await safeErrorMessage(res)) ?? 'No pudimos analizar el contrato.')
  }
  const body = (await res.json()) as StartAnalysisResponse
  if (!body.jobId) {
    throw new ApiError('No pudimos analizar el contrato.')
  }
  return body.jobId
}

/**
 * Polls until the analysis finishes, fails, or we give up waiting.
 *
 * A transient network blip during a two-minute poll shouldn't destroy an analysis that is still
 * running and already paid for, so failed polls are tolerated and retried; only a run of
 * consecutive failures is treated as fatal.
 */
export async function waitForAnalysis(jobId: string): Promise<AnalysisResult> {
  const deadline = Date.now() + POLL_TIMEOUT_MS
  let consecutiveFailures = 0

  while (Date.now() < deadline) {
    await sleep(POLL_INTERVAL_MS)

    let res: Response
    try {
      res = await fetch(`${API_BASE_URL}/analyze/${encodeURIComponent(jobId)}`)
    } catch {
      if (++consecutiveFailures >= 3) {
        throw new ApiError('Perdimos la conexión mientras analizábamos tu contrato. Probá de nuevo.')
      }
      continue
    }

    if (res.status === 404) {
      throw new ApiError(
        (await safeErrorMessage(res)) ?? 'No encontramos este análisis. Probá subir el contrato de nuevo.',
      )
    }
    if (!res.ok) {
      if (++consecutiveFailures >= 3) {
        throw new ApiError((await safeErrorMessage(res)) ?? 'No pudimos analizar el contrato.')
      }
      continue
    }

    consecutiveFailures = 0
    const job = (await res.json()) as AnalysisJobStatus
    if (job.status === 'done') {
      return job.result
    }
    if (job.status === 'error') {
      throw new ApiError(job.error || 'No pudimos analizar el contrato.')
    }
    // status === 'pending' — keep waiting.
  }

  throw new ApiError('El análisis está tardando más de lo esperado. Probá de nuevo en unos minutos.')
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
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
