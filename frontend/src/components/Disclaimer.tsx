// T14 draft copy. Still a draft — not a substitute for the human/lawyer review the plan calls for
// before launch (T14's "Owner: Ilia" line) — but the factual claims in it now match what the
// system actually does, checked against the code rather than written from memory:
//   - 24h  = the uploads bucket's S3 lifecycle rule (LeaseLensStorageStack)
//   - 48h  = OcrTextCache.TTL_HOURS, which holds the *full transcribed contract text* for scans,
//            i.e. longer than the PDF itself survives. The earlier draft never mentioned this and
//            so promised a shorter retention window than reality.
//   - 30d  = AnalyzeContractHandler.RESULTS_CACHE_TTL_DAYS. That stored result embeds verbatim
//            clauseQuote excerpts, so describing it as merely "el resultado" keyed by a hash read
//            as more anonymous than it is.
//   - the cross-border paragraph is new: contract text (and page images for scans) goes to
//            OpenRouter -> Anthropic/Google in the US. Ley 18.331 art. 13 requires disclosing
//            recipients, and art. 23 makes the transfer itself conditional on a legal basis —
//            per URCDP Resolución 63/023 the US is adequate only for Data Privacy Framework
//            participants, and as of 2026-08-16 neither Anthropic nor OpenRouter is listed.
// Open question for the review, deliberately NOT decided here because it changes the upload flow
// rather than the copy: whether art. 23's "consentimiento inequívoco" needs an explicit checkbox
// before upload instead of this passive notice. See the private T14 research + lawyer checklist.
// Also still missing, deliberately: art. 13 also requires naming the controller's identity and
// address plus a channel for access/rectification/deletion requests. Those are facts about Ilia
// (or a future legal entity), not copy an assistant can invent, so they are left out rather than
// filled with a plausible-looking placeholder that could ship by accident.
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
        <strong>Cómo tratamos tu contrato.</strong> No pedimos cuenta ni datos de identidad: el
        análisis es anónimo. Para poder analizarlo, el texto de tu contrato —y, si subís una foto
        o un escaneo, las imágenes de sus páginas— se envía a proveedores de inteligencia
        artificial ubicados en Estados Unidos. Al subir un archivo aceptás esa transferencia.
      </p>
      <p className="disclaimer__privacy">
        <strong>Cuánto tiempo guardamos cada cosa.</strong> El archivo original se borra de
        nuestro almacenamiento a las 24 horas. Si el contrato es escaneado, su texto transcrito se
        conserva hasta 48 horas para no repetir el procesamiento si volvés a intentarlo. El
        resultado del análisis —que incluye citas textuales de tu contrato— se conserva hasta 30
        días, identificado por un código derivado del texto y no por tu identidad, para mostrarte
        el mismo resultado si subís el mismo contrato otra vez. Nunca guardamos el texto de tu
        contrato en nuestros registros técnicos.
      </p>
    </div>
  )
}
