interface LicenseKeyInputProps {
  value: string
  onChange: (value: string) => void
}

/**
 * Access key entry (T18). Shown above the upload zone, which stays disabled until a key is typed.
 * The key itself is validated server-side when the analysis is requested — a wrong or used-up key
 * comes back as a normal error message — so this only checks that something was entered.
 */
export default function LicenseKeyInput({ value, onChange }: LicenseKeyInputProps) {
  return (
    <div className="license">
      <label htmlFor="license-key" className="license__label">
        Clave de acceso
      </label>
      <input
        type="text"
        id="license-key"
        className="license__input"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder="Pegá tu clave acá"
        autoComplete="off"
        autoCapitalize="none"
        spellCheck={false}
      />
    </div>
  )
}
