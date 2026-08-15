import { useRef, useState, type DragEvent } from 'react'
import { MAX_UPLOAD_SIZE_BYTES } from '../api'

interface UploadZoneProps {
  onFileSelected: (file: File) => void
  disabled: boolean
}

const MAX_SIZE_LABEL = `${Math.round(MAX_UPLOAD_SIZE_BYTES / (1024 * 1024))} MB`

export default function UploadZone({ onFileSelected, disabled }: UploadZoneProps) {
  const [isDragOver, setIsDragOver] = useState(false)
  const [validationError, setValidationError] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  function validateAndSelect(file: File | undefined) {
    if (!file) return
    if (file.type !== 'application/pdf') {
      setValidationError('El archivo debe ser un PDF.')
      return
    }
    if (file.size > MAX_UPLOAD_SIZE_BYTES) {
      setValidationError(`El archivo supera el tamaño máximo permitido (${MAX_SIZE_LABEL}).`)
      return
    }
    setValidationError(null)
    onFileSelected(file)
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setIsDragOver(false)
    if (disabled) return
    validateAndSelect(event.dataTransfer.files[0])
  }

  return (
    <div className="upload-zone-wrapper">
      <div
        className={`upload-zone${isDragOver ? ' upload-zone--drag-over' : ''}${disabled ? ' upload-zone--disabled' : ''}`}
        onDragOver={(event) => {
          event.preventDefault()
          if (!disabled) setIsDragOver(true)
        }}
        onDragLeave={() => setIsDragOver(false)}
        onDrop={handleDrop}
        onClick={() => !disabled && inputRef.current?.click()}
        role="button"
        tabIndex={disabled ? -1 : 0}
        aria-disabled={disabled}
        onKeyDown={(event) => {
          if (!disabled && (event.key === 'Enter' || event.key === ' ')) {
            event.preventDefault()
            inputRef.current?.click()
          }
        }}
      >
        <p className="upload-zone__title">Arrastrá tu contrato acá</p>
        <p className="upload-zone__subtitle">o tocá para elegir un archivo PDF (máx. {MAX_SIZE_LABEL})</p>
        <input
          ref={inputRef}
          type="file"
          accept="application/pdf"
          className="upload-zone__input"
          disabled={disabled}
          onChange={(event) => validateAndSelect(event.target.files?.[0])}
        />
      </div>
      {validationError && <p className="field-error">{validationError}</p>}
    </div>
  )
}
