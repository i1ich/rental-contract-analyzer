// T14 draft copy: expands on the earlier placeholder with the scope disclaimer and a privacy
// note that names the actual retention windows (see AnalyzeContractHandler.RESULTS_CACHE_TTL_DAYS
// and the uploads bucket's 24h S3 lifecycle rule in LeaseLensStorageStack) rather than vague
// language, since specific claims here are easier to keep honest as the backend changes than
// vague ones. This is still a draft, not a substitute for the human/lawyer review the plan calls
// for before launch (T14's "Owner: Ilia" line) — flagging that explicitly rather than silently
// treating this as final.
export default function Disclaimer() {
  return (
    <div className="disclaimer">
      <p>
        <strong>Herramienta educativa, no es asesoramiento legal.</strong> Este análisis fue
        generado automáticamente por inteligencia artificial y puede contener errores, omisiones
        o interpretaciones incorrectas. No reemplaza la opinión de un abogado. Ante cualquier duda
        concreta, y especialmente antes de firmar, consultá a un profesional.
      </p>
      <p className="disclaimer__privacy">
        Pensado para contratos de alquiler de vivienda en Montevideo, Uruguay — su análisis puede
        no ser preciso para otro tipo de contrato o jurisdicción.
      </p>
      <p className="disclaimer__privacy">
        Tu contrato se procesa de forma anónima: no pedimos cuenta ni guardamos el documento
        original. El archivo se elimina de nuestro almacenamiento a las 24 horas. Guardamos el
        resultado del análisis (identificado por un hash del texto, no por tu identidad) hasta 30
        días, para poder mostrarte el mismo resultado si volvés a subir el mismo contrato.
      </p>
    </div>
  )
}
