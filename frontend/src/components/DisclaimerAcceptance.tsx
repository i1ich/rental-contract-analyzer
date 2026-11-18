import type { ReactNode } from 'react'
import disclaimerText from '../legal/disclaimer.es.md?raw'

interface DisclaimerAcceptanceProps {
  checked: boolean
  onChange: (checked: boolean) => void
}

/** Turns **bold** spans of the markdown source into <strong>; the file uses nothing else inline. */
function inline(text: string): ReactNode[] {
  return text.split(/\*\*(.+?)\*\*/g).map((part, i) => (i % 2 === 1 ? <strong key={i}>{part}</strong> : part))
}

/** Minimal renderer for the disclaimer file: one heading, then blocks separated by blank lines. */
function FullText() {
  const blocks = disclaimerText
    .replace(/\r\n/g, '\n')
    .split(/\n\s*\n/)
    .map((block) => block.replace(/\n\s*/g, ' ').trim())
    .filter(Boolean)
  return (
    <div className="legal__text">
      {blocks.map((block, i) =>
        block.startsWith('# ') ? (
          <h2 key={i}>{block.slice(2)}</h2>
        ) : (
          <p key={i}>{inline(block)}</p>
        ),
      )}
    </div>
  )
}

/**
 * Explicit acceptance of the full disclaimer / release of liability. Separate from the
 * cross-border TransferConsent checkbox on purpose: different act, different legal basis.
 *
 * The full text (src/legal/disclaimer.es.md, the very file whose version the API enforces) sits
 * right under the checkbox. Never pre-checked, and cleared on every new analysis (see App.reset)
 * because the API requires the acceptance on every request. The server is the real gate; this is
 * what makes the acceptance an informed one.
 *
 * Draft copy: not yet lawyer-reviewed.
 */
export default function DisclaimerAcceptance({ checked, onChange }: DisclaimerAcceptanceProps) {
  return (
    <div className="legal">
      <div className="consent">
        <input
          type="checkbox"
          id="disclaimer-acceptance"
          className="consent__checkbox"
          checked={checked}
          onChange={(event) => onChange(event.target.checked)}
        />
        <label htmlFor="disclaimer-acceptance" className="consent__label">
          Leí el aviso legal completo y entiendo que <strong>esto no es asesoramiento legal</strong>,
          que el análisis puede tener errores y que <strong>libero al operador de responsabilidad</strong>{' '}
          por las decisiones que tome con base en él.
        </label>
      </div>
      <details className="legal__details" open>
        <summary>Aviso legal completo</summary>
        <FullText />
      </details>
    </div>
  )
}
