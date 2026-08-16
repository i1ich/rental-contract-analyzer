import { useState } from 'react'
import UploadZone from './components/UploadZone'
import ResultsView from './components/ResultsView'
import Disclaimer from './components/Disclaimer'
import TransferConsent from './components/TransferConsent'
import { ApiError, requestUploadUrl, startAnalysis, uploadFile, waitForAnalysis } from './api'
import type { AnalysisResult } from './types'
import { MOCK_ANALYSIS_RESULT } from './mockData'
import './App.css'

type Stage = 'idle' | 'uploading' | 'analyzing' | 'done' | 'error'

export default function App() {
  const [stage, setStage] = useState<Stage>('idle')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [result, setResult] = useState<AnalysisResult | null>(null)
  // Consent to the cross-border transfer — see TransferConsent for why this gate exists.
  const [transferConsent, setTransferConsent] = useState(false)

  function reset() {
    setStage('idle')
    setErrorMessage(null)
    setResult(null)
    // Cleared deliberately: consent is given per transfer, so analyzing another contract asks
    // again rather than carrying over the previous contract's answer.
    setTransferConsent(false)
  }

  async function handleFileSelected(file: File) {
    setErrorMessage(null)
    try {
      setStage('uploading')
      const { uploadUrl, objectKey, requiredContentType } = await requestUploadUrl(file.size)
      await uploadFile(uploadUrl, requiredContentType, file)

      setStage('analyzing')
      // Two steps rather than one: the API accepts the job and hands back an id, and the result
      // is collected by polling. See api.ts — a real analysis outlives API Gateway's 29s cap.
      const jobId = await startAnalysis(objectKey)
      const analysis = await waitForAnalysis(jobId)

      setResult(analysis)
      setStage('done')
    } catch (error) {
      setErrorMessage(error instanceof ApiError ? error.message : 'Ocurrió un error inesperado. Probá de nuevo.')
      setStage('error')
    }
  }

  return (
    <main className="app">
      <header className="app__header">
        <h1>LeaseLens</h1>
        <p>Revisá tu contrato de alquiler antes de firmarlo.</p>
      </header>

      {stage === 'idle' && (
        <>
          <TransferConsent checked={transferConsent} onChange={setTransferConsent} />
          <UploadZone
            onFileSelected={handleFileSelected}
            disabled={!transferConsent}
            disabledReason="Para poder analizar tu contrato necesitamos tu consentimiento: marcá la casilla de arriba."
          />
          {import.meta.env.DEV && (
            <button type="button" className="button button--dev" onClick={() => { setResult(MOCK_ANALYSIS_RESULT); setStage('done') }}>
              Ver datos de ejemplo (solo dev)
            </button>
          )}
          <Disclaimer />
        </>
      )}

      {(stage === 'uploading' || stage === 'analyzing') && (
        <div className="status-panel" role="status" aria-live="polite">
          <div className="spinner" aria-hidden="true" />
          {stage === 'uploading' ? (
            <p>Subiendo tu contrato…</p>
          ) : (
            <>
              {/* A full analysis takes a minute or two. Saying "unos segundos" (as this did while
                  the call was synchronous) trains people to think it's stuck and reload, which
                  throws away a paid analysis mid-flight. */}
              <p>Analizando tu contrato…</p>
              <p className="status-panel__hint">
                Esto suele tardar entre uno y dos minutos. No cierres esta página.
              </p>
            </>
          )}
        </div>
      )}

      {stage === 'error' && (
        <div className="status-panel status-panel--error" role="alert">
          <p>{errorMessage}</p>
          <button type="button" className="button" onClick={reset}>
            Intentar de nuevo
          </button>
        </div>
      )}

      {stage === 'done' && result && <ResultsView result={result} onAnalyzeAnother={reset} />}
    </main>
  )
}
