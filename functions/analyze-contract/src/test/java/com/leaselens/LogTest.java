package com.leaselens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The logging format is only useful if it is machine-parseable, so it is worth a test: a line
 * that stops being valid JSON silently degrades every CloudWatch Logs Insights query built on it.
 */
class LogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clearContext() {
        Log.clearCorrelationId();
    }

    @Test
    void rendersValidJsonWithTypedValues() throws Exception {
        JsonNode node = MAPPER.readTree(
                Log.line("info", "analysis_completed", null,
                        "durationMs", 812L, "cacheHit", false, "source", "vision-ocr"));

        assertEquals("info", node.path("level").asText());
        assertEquals("analysis_completed", node.path("event").asText());
        // Numbers and booleans stay unquoted, so `stats avg(durationMs)` works without a cast.
        assertTrue(node.path("durationMs").isNumber());
        assertTrue(node.path("cacheHit").isBoolean());
        assertEquals("vision-ocr", node.path("source").asText());
    }

    @Test
    void stampsCorrelationIdOnEveryLine() throws Exception {
        Log.setCorrelationId("job-123");
        JsonNode node = MAPPER.readTree(Log.line("info", "analysis_started", null));
        assertEquals("job-123", node.path("correlationId").asText());

        Log.clearCorrelationId();
        JsonNode afterClear = MAPPER.readTree(Log.line("info", "analysis_started", null));
        assertTrue(afterClear.path("correlationId").isMissingNode());
    }

    @Test
    void escapesQuotesAndNewlinesRatherThanBreakingTheLine() throws Exception {
        String rendered = Log.line("warn", "request_rejected", null,
                "reason", "he said \"no\"\nand left\ttabbed");

        assertEquals(1, rendered.lines().count(), "a log record must stay on one line");
        assertEquals("he said \"no\"\nand left\ttabbed",
                MAPPER.readTree(rendered).path("reason").asText());
    }

    @Test
    void compactsExceptionsOntoOneLine() throws Exception {
        Exception cause = new IllegalStateException("underlying cause");
        Exception thrown = new RuntimeException("outer failure", cause);

        String rendered = Log.line("error", "analysis_failed", thrown, "status", 500);
        assertEquals(1, rendered.lines().count(), "a stack trace must not span log records");

        JsonNode node = MAPPER.readTree(rendered);
        assertEquals("java.lang.RuntimeException", node.path("exceptionClass").asText());
        assertEquals("outer failure", node.path("exceptionMessage").asText());
        assertTrue(node.path("stack").asText().contains("caused by java.lang.IllegalStateException"));
    }

    @Test
    void truncatesOversizedValues() throws Exception {
        String rendered = Log.line("info", "text_extracted", null, "sample", "x".repeat(5_000));
        String value = MAPPER.readTree(rendered).path("sample").asText();

        assertTrue(value.endsWith("...[truncated]"));
        assertFalse(value.length() > 600, "a single value must not flood the log group");
    }

    @Test
    void ignoresATrailingKeyWithNoValue() throws Exception {
        // Defensive: a mistyped call site should lose the dangling key, never throw inside logging.
        JsonNode node = MAPPER.readTree(Log.line("info", "job_accepted", null, "objectKey"));
        assertEquals("job_accepted", node.path("event").asText());
        assertTrue(node.path("objectKey").isMissingNode());
    }
}
