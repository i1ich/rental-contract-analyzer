import type { Finding } from '../types'

const SEVERITY_LABEL: Record<Finding['severity'], string> = {
  red: 'Alerta',
  yellow: 'A revisar',
  green: 'Favorable',
}

export default function FindingCard({ finding }: { finding: Finding }) {
  const hasQuote = finding.clauseQuote.trim().length > 0

  return (
    <li className={`finding-card finding-card--${finding.severity}`}>
      <div className="finding-card__header">
        <span className={`severity-dot severity-dot--${finding.severity}`} aria-hidden="true" />
        <span className="finding-card__severity-label">{SEVERITY_LABEL[finding.severity]}</span>
        <span className="finding-card__location">{finding.location}</span>
      </div>

      {hasQuote ? (
        <blockquote className="finding-card__quote">&ldquo;{finding.clauseQuote}&rdquo;</blockquote>
      ) : (
        <p className="finding-card__quote finding-card__quote--absent">Cláusula ausente en el contrato</p>
      )}

      <p className="finding-card__explanation">{finding.plainExplanation}</p>
      <p className="finding-card__why">{finding.whyItMatters}</p>
    </li>
  )
}
