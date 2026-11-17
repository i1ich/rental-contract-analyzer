import type { AnalysisResult } from '../types'
import FindingCard from './FindingCard'
import Disclaimer from './Disclaimer'

interface ResultsViewProps {
  result: AnalysisResult
  onAnalyzeAnother: () => void
  /** License balance after this analysis; null/undefined when unknown (e.g. sample data). */
  remainingUses?: number | null
}

export default function ResultsView({ result, onAnalyzeAnother, remainingUses }: ResultsViewProps) {
  return (
    <div className="results-view">
      <section className="results-view__summary">
        <h2>Resumen</h2>
        <p>{result.summary}</p>
        {result.cachedAt && (
          <p className="results-view__cached-note">
            Este contrato ya había sido analizado antes — resultado servido desde caché.
          </p>
        )}
      </section>

      {result.findings.length > 0 ? (
        <ul className="results-view__findings">
          {result.findings.map((finding, index) => (
            <FindingCard key={index} finding={finding} />
          ))}
        </ul>
      ) : (
        <p className="results-view__no-findings">No se encontraron cláusulas para destacar.</p>
      )}

      <Disclaimer />

      {remainingUses != null && (
        <p className="results-view__remaining">
          {remainingUses === 0
            ? 'Usaste todos los análisis de tu clave de acceso.'
            : `Te quedan ${remainingUses} análisis con tu clave de acceso.`}
        </p>
      )}

      <button type="button" className="button button--secondary" onClick={onAnalyzeAnother}>
        Analizar otro contrato
      </button>
    </div>
  )
}
