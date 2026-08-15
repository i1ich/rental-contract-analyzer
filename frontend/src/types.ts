// Mirrors the backend's AnalysisResult/Finding shape (functions/analyze-contract/src/main/java/com/leaselens/model).
export type Severity = 'red' | 'yellow' | 'green'

export interface Finding {
  severity: Severity
  clauseQuote: string
  location: string
  plainExplanation: string
  whyItMatters: string
}

export interface AnalysisResult {
  summary: string
  findings: Finding[]
  cachedAt: string | null
}
