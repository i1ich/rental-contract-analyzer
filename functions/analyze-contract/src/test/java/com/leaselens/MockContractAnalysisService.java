package com.leaselens;

import com.leaselens.model.AnalysisResult;
import com.leaselens.model.Finding;
import com.leaselens.service.ContractAnalysisService;

import java.util.List;

/**
 * Test-only stand-in for {@link ContractAnalysisService} that returns a fixed, deterministic
 * result. It never calls out to Claude — used to test the handler plumbing (caching, hashing,
 * status codes, quote-preservation) without any network access or API key.
 *
 * The findings' clauseQuote values are deliberately snippets copied verbatim from the two mock
 * golden-set fixtures, so tests can assert that quotes returned by the handler are always a
 * substring of the original contract text — the core "no hallucinated quotes" promise of the
 * product, proven here at the plumbing level even though the LLM call itself is mocked.
 */
public class MockContractAnalysisService implements ContractAnalysisService {

    @Override
    public AnalysisResult analyze(String contractText) {
        Finding finding = new Finding();
        finding.setSeverity("red");
        finding.setLocation("Cláusula detectada automáticamente (mock)");
        finding.setPlainExplanation("Esta es una cláusula de prueba generada por el servicio simulado.");
        finding.setWhyItMatters("Sirve para validar que las citas se preservan correctamente.");

        // Pick a verbatim substring from whichever fixture was actually passed in, so the
        // "clauseQuote must be a substring of the input" invariant holds for either fixture.
        if (contractText.contains("DEPÓSITO EN GARANTÍA")) {
            finding.setClauseQuote(
                    "la suma equivalente a SEIS (6) meses de alquiler, es decir $180.000, la cual el\n"
                            + "Arrendador podrá retener a su exclusivo criterio al finalizar el contrato");
        } else if (contractText.contains("RENOVACIÓN")) {
            finding.setClauseQuote(
                    "se entenderá tácitamente\n"
                            + "prorrogado sin límite de veces, quedando el Arrendatario obligado en los mismos términos\n"
                            + "indefinidamente");
        } else {
            // Fallback for arbitrary input: take a real substring from the middle of the text
            // so the invariant still holds even for unrecognized fixtures.
            int start = Math.min(50, Math.max(0, contractText.length() - 1));
            int end = Math.min(contractText.length(), start + 40);
            finding.setClauseQuote(contractText.substring(start, end));
        }

        AnalysisResult result = new AnalysisResult();
        result.setSummary("Resumen de prueba (mock): se detectaron cláusulas potencialmente riesgosas.");
        result.setFindings(List.of(finding));
        return result;
    }
}
