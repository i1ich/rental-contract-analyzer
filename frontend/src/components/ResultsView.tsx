import type { AnalysisResult } from '../types'
import FindingCard from './FindingCard'
import Disclaimer from './Disclaimer'

interface ResultsViewProps {
  result: AnalysisResult
  onAnalyzeAnother: () => void
}

export default function ResultsView({ result, onAnalyzeAnother }: ResultsViewProps) {
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

      <button type="button" className="button button--secondary" onClick={onAnalyzeAnother}>
        Analizar otro contrato
      </button>
    </div>
  )
}
