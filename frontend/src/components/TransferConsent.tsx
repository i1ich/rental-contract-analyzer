interface TransferConsentProps {
  checked: boolean
  onChange: (checked: boolean) => void
}

/**
 * Explicit consent to the cross-border transfer, required before any file can leave the browser.
 *
 * Ley 18.331 art. 23 permits transferring personal data to a non-adequate destination where the
 * data subject "haya dado su consentimiento inequívoco a la transferencia prevista". Per URCDP
 * Resolución 63/023 the US counts as adequate only for organisations on the US Data Privacy
 * Framework list, and as of 2026-08-16 that list has Google LLC active but no entry for Anthropic
 * or OpenRouter — both of which every analysis passes through. So consent is the basis this
 * product actually relies on, and a paragraph of explanatory text sitting next to an upload button
 * is a weak candidate for "inequívoco": this is a deliberate, unticked act instead.
 *
 * Rendered ABOVE the upload zone on purpose — art. 13 requires the information to reach the person
 * *before* the data is collected, and art. 13's final paragraph requires consent bundled with other
 * declarations to appear "en forma expresa y destacada".
 *
 * Never pre-checked, and reset on every new analysis (see App.reset) so each upload carries its own
 * affirmative act rather than inheriting one from a previous contract.
 *
 * Still draft copy pending the lawyer review this task calls for — the exact wording of the consent
 * line is question 1 on the reviewer checklist in the private vault.
 */
export default function TransferConsent({ checked, onChange }: TransferConsentProps) {
  return (
    <div className="consent">
      <input
        type="checkbox"
        id="transfer-consent"
        className="consent__checkbox"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
      />
      <label htmlFor="transfer-consent" className="consent__label">
        Entiendo que, para analizarlo, el texto de mi contrato —y las imágenes de sus páginas si
        subo una foto o un escaneo— se enviará a proveedores de inteligencia artificial ubicados en
        Estados Unidos, y <strong>acepto esa transferencia</strong>.
      </label>
    </div>
  )
}
