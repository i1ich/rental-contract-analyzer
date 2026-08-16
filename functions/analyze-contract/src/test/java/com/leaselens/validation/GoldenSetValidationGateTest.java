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
        List<Long> latenciesMs = new ArrayList<>();
        List<String> analysisFailures = new ArrayList<>();
        StringBuilder report = new StringBuilder("\n=== T12 golden-set validation gate ===\n");

        for (Path annotationFile : annotationFiles) {
            JsonNode annotation = MAPPER.readTree(Files.readString(annotationFile));
            String contractId = annotation.path("contractId").asText(annotationFile.getFileName().toString());
            String contractText = Files.readString(dir.resolve(annotation.path("contractFile").asText()));

            // Live LLM call — parse failures here are themselves a gate failure
            // ("valid JSON every time"). Recorded and carried on rather than aborting: bailing on
            // the first bad contract used to discard the recall/latency data already paid for on
            // the earlier ones, which is exactly what happened evaluating claude-3-haiku (v0.6)
            // and claude-haiku-4.5 (v0.7) — both times the run cost real money and produced a
            // single line of usable information. The gate still fails at the end.
            AnalysisResult result;
            long startedAt = System.nanoTime();
            try {
                result = service.analyze(contractText);
            } catch (RuntimeException e) {
                long failedAfterMs = (System.nanoTime() - startedAt) / 1_000_000;
                latenciesMs.add(failedAfterMs);
                analysisFailures.add(contractId + ": " + e.getMessage());
                // Count this contract's expected findings as missed — the model returned nothing
                // usable, so its real recall here is zero, and quietly excluding it would flatter
                // the overall number.
                for (JsonNode ef : annotation.path("expectedFindings")) {
                    expectedTotal++;
                    misses.add(contractId + ": " + ef.path("id").asText());
                }
                report.append(String.format(Locale.ROOT, "%-28s ANALYSIS FAILED after %.1fs — %s%n",
                        contractId, failedAfterMs / 1000.0, e.getMessage()));
                continue;
            }
            // Measured because recall alone can't tell you whether a model is shippable: this gate
            // calls the service directly, so it never crosses API Gateway's hard, unconfigurable
            // 29s integration timeout the way production does. claude-sonnet-5 passes this gate on
            // quality and still 504s every cache-miss request live (~83-92s per contract, measured
            // 2026-08-16). Reported, not asserted — picking the cutoff is a product decision.
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            latenciesMs.add(elapsedMs);

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
            report.append(String.format(Locale.ROOT, "%-28s recall %d/%d, findings returned: %d, %.1fs%n",
                    contractId, matched, expected, result.getFindings().size(), elapsedMs / 1000.0));
        }

        double recall = expectedTotal == 0 ? 0 : (double) matchedTotal / expectedTotal;
        report.append(String.format(Locale.ROOT, "OVERALL recall: %.0f%% (%d/%d), hallucinated quotes: %d%n",
                recall * 100, matchedTotal, expectedTotal, hallucinations.size()));
        if (!latenciesMs.isEmpty()) {
            long worst = latenciesMs.stream().mapToLong(Long::longValue).max().orElse(0);
            double mean = latenciesMs.stream().mapToLong(Long::longValue).average().orElse(0);
            report.append(String.format(Locale.ROOT,
                    "LATENCY model=%s mean %.1fs, worst %.1fs (API Gateway hard cap: 29s)%n",
                    System.getenv().getOrDefault("OPENROUTER_MODEL", "(from SSM/default)"),
                    mean / 1000.0, worst / 1000.0));
        }
        if (!misses.isEmpty()) report.append("Missed: ").append(misses).append('\n');
        if (!hallucinations.isEmpty()) report.append("Hallucinated: ").append(hallucinations).append('\n');
        if (!analysisFailures.isEmpty()) report.append("Analysis failures: ").append(analysisFailures).append('\n');
        System.out.println(report);

        assertTrue(analysisFailures.isEmpty(),
                "Model did not return usable output for every contract (the gate's \"valid JSON every "
                        + "time\" criterion): " + analysisFailures);
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
