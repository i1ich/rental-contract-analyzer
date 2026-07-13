package com.leaselens.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaselens.model.AnalysisResult;
import com.leaselens.model.Finding;
import com.leaselens.service.OpenRouterAnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T12 — Validation gate against the real golden set (see mvp3 plan).
 *
 * Runs every contract in the private golden-set directory through the REAL
 * {@link OpenRouterAnalysisService} (live call, routed through OpenRouter) and compares the
 * findings against the human annotations.
 *
 * Gate criteria (the ship blocker): overall recall of annotated findings >= 80% AND zero
 * hallucinated quotes (every non-empty clauseQuote must be a verbatim substring of the
 * contract text, modulo whitespace/case/accents).
 *
 * Deliberately opt-in: it costs money and needs secrets, so it only runs when
 * GOLDEN_SET_DIR is set (plus OPENROUTER_API_KEY, or AWS creds + OPENROUTER_API_KEY_PARAM).
 * The golden set lives OUTSIDE this repo because it contains real PII:
 *   $env:GOLDEN_SET_DIR = "...\rental-contract-analyzer-private\golden-set"
 *   $env:OPENROUTER_API_KEY = "sk-or-v1-..."
 *   .\mvnw.cmd -pl functions/analyze-contract test "-Dtest=GoldenSetValidationGateTest"
 */
@EnabledIfEnvironmentVariable(named = "GOLDEN_SET_DIR", matches = ".+")
class GoldenSetValidationGateTest {

    private static final double RECALL_GATE = 0.80;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void goldenSetRecallAndNoHallucinatedQuotes() throws IOException {
        Path dir = Path.of(System.getenv("GOLDEN_SET_DIR"));
        assertTrue(Files.isDirectory(dir), "GOLDEN_SET_DIR is not a directory: " + dir);

        List<Path> annotationFiles;
        try (Stream<Path> files = Files.list(dir)) {
            annotationFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".annotations.json"))
                    .sorted()
                    .toList();
        }
        assertTrue(annotationFiles.size() >= 3,
                "Golden set must contain >= 3 annotated contracts, found " + annotationFiles.size());

        OpenRouterAnalysisService service = new OpenRouterAnalysisService();

        int expectedTotal = 0;
        int matchedTotal = 0;
        List<String> hallucinations = new ArrayList<>();
        List<String> misses = new ArrayList<>();
        StringBuilder report = new StringBuilder("\n=== T12 golden-set validation gate ===\n");

        for (Path annotationFile : annotationFiles) {
            JsonNode annotation = MAPPER.readTree(Files.readString(annotationFile));
            String contractId = annotation.path("contractId").asText(annotationFile.getFileName().toString());
            String contractText = Files.readString(dir.resolve(annotation.path("contractFile").asText()));

            // Live LLM call — parse failures here are themselves a gate failure
            // ("valid JSON every time").
            AnalysisResult result;
            try {
                result = service.analyze(contractText);
            } catch (RuntimeException e) {
                fail("Analysis failed (invalid JSON or API error) for " + contractId + ": " + e.getMessage());
                return;
            }

            String normalizedContract = normalize(contractText);
            String haystack = normalize(findingsAsText(result));

            for (Finding f : result.getFindings()) {
                String quote = f.getClauseQuote();
                if (quote != null && !quote.isBlank() && !normalizedContract.contains(normalize(quote))) {
                    hallucinations.add(contractId + ": \"" + quote + "\"");
                }
            }

            int expected = 0;
            int matched = 0;
            for (JsonNode ef : annotation.path("expectedFindings")) {
                expected++;
                boolean hit = false;
                for (JsonNode term : ef.path("matchAnyOf")) {
                    if (haystack.contains(normalize(term.asText()))) {
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    matched++;
                } else {
                    misses.add(contractId + ": " + ef.path("id").asText());
                }
            }
            expectedTotal += expected;
            matchedTotal += matched;
            report.append(String.format(Locale.ROOT, "%-28s recall %d/%d, findings returned: %d%n",
                    contractId, matched, expected, result.getFindings().size()));
        }

        double recall = expectedTotal == 0 ? 0 : (double) matchedTotal / expectedTotal;
        report.append(String.format(Locale.ROOT, "OVERALL recall: %.0f%% (%d/%d), hallucinated quotes: %d%n",
                recall * 100, matchedTotal, expectedTotal, hallucinations.size()));
        if (!misses.isEmpty()) report.append("Missed: ").append(misses).append('\n');
        if (!hallucinations.isEmpty()) report.append("Hallucinated: ").append(hallucinations).append('\n');
        System.out.println(report);

        assertTrue(hallucinations.isEmpty(), "Hallucinated quotes found: " + hallucinations);
        assertTrue(recall >= RECALL_GATE, String.format(Locale.ROOT,
                "Recall %.0f%% below the %.0f%% gate. Missed: %s — loop back to T7 (prompt).",
                recall * 100, RECALL_GATE * 100, misses));
    }

    private static String findingsAsText(AnalysisResult result) {
        StringBuilder sb = new StringBuilder(result.getSummary() == null ? "" : result.getSummary());
        for (Finding f : result.getFindings()) {
            for (String s : new String[]{f.getClauseQuote(), f.getPlainExplanation(), f.getWhyItMatters()}) {
                if (s != null) sb.append('\n').append(s);
            }
        }
        return sb.toString();
    }

    /** Lowercase, strip accents, collapse all whitespace — so quote matching survives
     *  line-wrapping and tilde differences without letting real fabrications through. */
    private static String normalize(String s) {
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
