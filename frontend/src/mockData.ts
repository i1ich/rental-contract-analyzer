import type { AnalysisResult } from './types'

// Dev-only fixture (fictional contract) used by App.tsx's "Ver datos de ejemplo" button, so the
// results view can be exercised in the browser without a deployed API (T9/T16 aren't live yet).
// Never referenced outside `import.meta.env.DEV` branches.
export const MOCK_ANALYSIS_RESULT: AnalysisResult = {
  summary:
    'Contrato con dos cláusulas de alto riesgo (aceleración de alquileres y mantenimiento total a cargo del inquilino) y una a negociar (garantía de 6 meses, por encima del tope legal). El resto de las condiciones son razonables.',
  findings: [
    {
      severity: 'red',
      clauseQuote:
        'En caso de rescisión anticipada por parte del arrendatario, éste deberá abonar la totalidad de los alquileres restantes hasta la finalización del plazo contractual, en una sola partida.',
      location: 'Cláusula 8ª',
      plainExplanation:
        'Si te vas antes de que termine el contrato, tenés que pagar de una sola vez todo lo que quedaba del alquiler hasta el final del plazo.',
      whyItMatters:
        'Esto es una cláusula de aceleración de alquileres: te deja atado económicamente aunque encuentres otro inquilino que quiera ocupar el lugar. Convendría negociar una cláusula de salida anticipada con preaviso razonable.',
    },
    {
      severity: 'red',
      clauseQuote:
        'El arrendatario será responsable de todas las reparaciones del inmueble, cualquiera sea su causa u origen, incluyendo defectos estructurales.',
      location: 'Cláusula 12ª',
      plainExplanation:
        'El contrato te hace responsable de arreglar todo, incluso problemas estructurales que no tienen que ver con el uso que le das a la vivienda.',
      whyItMatters:
        'Por el Código Civil, las reparaciones por desgaste normal y los defectos estructurales le corresponden al propietario. Esta cláusula intenta trasladarte una responsabilidad que no es tuya.',
    },
    {
      severity: 'yellow',
      clauseQuote: 'Se establece como garantía el equivalente a seis (6) meses de alquiler.',
      location: 'Cláusula 5ª',
      plainExplanation: 'Te piden una garantía de 6 meses de alquiler.',
      whyItMatters:
        'Para contratos bajo el régimen protegido (Decreto-Ley 14.219), el tope legal de garantía es de 5 meses. Vale la pena verificar el régimen del contrato y, si corresponde, negociar el monto.',
    },
    {
      severity: 'green',
      clauseQuote:
        'Las reparaciones derivadas de vicios ocultos o del deterioro natural del inmueble estarán a cargo del arrendador.',
      location: 'Cláusula 12ª bis',
      plainExplanation: 'El propietario se hace cargo de arreglos por desgaste normal o problemas que ya venían de antes.',
      whyItMatters:
        'Esta cláusula sí está alineada con el Código Civil y es una protección real para vos como inquilino — vale la pena que quede así.',
    },
  ],
  cachedAt: null,
}
