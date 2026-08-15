// Placeholder copy — content ownership (and legal review) belongs to T14, which hasn't run yet.
// Kept here, always rendered, so no result screen can ship without *some* disclaimer in the
// meantime; swap the text for T14's reviewed copy once that lands, not the component itself.
export default function Disclaimer() {
  return (
    <div className="disclaimer">
      <p>
        <strong>Herramienta educativa, no es asesoramiento legal.</strong> Este análisis fue
        generado automáticamente y puede tener errores u omisiones. Ante dudas concretas,
        consultá a un abogado.
      </p>
      <p className="disclaimer__privacy">
        Tu contrato se procesa de forma anónima y se elimina de nuestro almacenamiento a las 24
        horas. No pedimos cuenta ni guardamos el documento original.
      </p>
    </div>
  )
}
