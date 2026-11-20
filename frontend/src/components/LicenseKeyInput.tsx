import { useEffect, useState } from 'react'
import { fetchLicenseStatus } from '../api'
import type { LicenseStatus } from '../api'

interface LicenseKeyInputProps {
  value: string
  onChange: (value: string) => void
}

// Keys look like "usr1_ABCDEFGH-JKLMNPQR"; nothing shorter than this can be one, so don't ask the
// server about half-typed input.
const MIN_KEY_LENGTH = 8
// Wait for typing/pasting to settle before checking, so one paste is one request.
const CHECK_DELAY_MS = 600

type Check =
  | { phase: 'idle' }
  | { phase: 'loading' }
  | { phase: 'done'; status: LicenseStatus }
  | { phase: 'error'; message: string }

/**
 * Access key entry (T18). Shown above the upload zone, which stays disabled until a key is typed.
 *
 * Also shows what is left of the key: as soon as a plausible key is pasted (or via the button) it
 * asks the read-only /license-status endpoint and displays remaining uses and the expiry
 * date. That check charges nothing. The authoritative gate is still server-side when the file is
 * uploaded and the analysis requested.
 */
export default function LicenseKeyInput({ value, onChange }: LicenseKeyInputProps) {
  const [check, setCheck] = useState<Check>({ phase: 'idle' })
  const [manualTick, setManualTick] = useState(0)

  const trimmed = value.trim()
  const checkable = trimmed.length >= MIN_KEY_LENGTH

  useEffect(() => {
    if (!checkable) {
      setCheck({ phase: 'idle' })
      return
    }
    let cancelled = false
    // The first run (manualTick 0) is debounced; a button press re-runs the effect immediately.
    const delay = manualTick === 0 ? CHECK_DELAY_MS : 0
    setCheck({ phase: 'loading' })
    const timer = setTimeout(() => {
      fetchLicenseStatus(trimmed).then(
        (status) => {
          if (!cancelled) setCheck({ phase: 'done', status })
        },
        (error: unknown) => {
          if (!cancelled) {
            setCheck({
              phase: 'error',
              message: error instanceof Error ? error.message : 'No pudimos verificar la clave.',
            })
          }
        },
      )
    }, delay)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [trimmed, checkable, manualTick])

  return (
    <div className="license">
      <label htmlFor="license-key" className="license__label">
        Clave de acceso
      </label>
      <div className="license__row">
        <input
          type="text"
          id="license-key"
          className="license__input"
          value={value}
          onChange={(event) => {
            // Typing starts a fresh debounced check rather than re-running an immediate one.
            setManualTick(0)
            onChange(event.target.value)
          }}
          placeholder="Pegá tu clave acá"
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
        />
        <button
          type="button"
          className="license__check"
          disabled={!checkable || check.phase === 'loading'}
          onClick={() => setManualTick((tick) => tick + 1)}
        >
          Ver saldo
        </button>
      </div>
      <div aria-live="polite">{renderStatus(check)}</div>
    </div>
  )
}

function renderStatus(check: Check) {
  switch (check.phase) {
    case 'idle':
      return null
    case 'loading':
      return <p className="license__status">Verificando clave…</p>
    case 'error':
      return <p className="license__status license__status--bad">{check.message}</p>
    case 'done':
      return <StatusLine status={check.status} />
  }
}

function StatusLine({ status }: { status: LicenseStatus }) {
  const expiry = status.expiresAt ? formatDate(status.expiresAt) : null

  if (status.state === 'revoked') {
    return <p className="license__status license__status--bad">Esta clave ya no está activa.</p>
  }
  if (status.state === 'expired') {
    return (
      <p className="license__status license__status--bad">
        Esta clave venció{expiry ? ` el ${expiry}` : ''}. Pedí una clave nueva.
      </p>
    )
  }
  if (status.state === 'exhausted') {
    return (
      <p className="license__status license__status--bad">
        Esta clave ya usó todos sus análisis ({status.totalUses} de {status.totalUses}).
      </p>
    )
  }
  return (
    <p className="license__status license__status--ok">
      Te quedan {status.remainingUses} de {status.totalUses} análisis
      {expiry ? ` · vence el ${expiry}` : ''}.
    </p>
  )
}

function formatDate(iso: string): string {
  // The backend expires keys at 00:00 UTC; show that calendar date rather than shifting it into
  // the viewer's timezone (which would show the 14th for anyone west of UTC).
  return new Date(iso).toLocaleDateString('es-AR', { day: 'numeric', month: 'long', timeZone: 'UTC' })
}
