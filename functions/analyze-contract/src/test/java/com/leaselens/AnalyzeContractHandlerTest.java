package com.leaselens;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises AnalyzeContractHandler end-to-end (parsing, extract-text seam, cache-miss path,
 * response shape) using fakes for every AWS-touching dependency, so it runs fully offline.
 *
 * These fixtures are fictional test data (see disclaimer header in each fixture file) — not
 * real contracts and not the T2/T7 golden set.
 */
class AnalyzeContractHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static String fixture01;
    private static String fixture02;

    @BeforeAll
    static void loadFixtures() throws IOException {
        fixture01 = readFixture("fixture-01-deposito-abusivo.txt");
        fixture02 = readFixture("fixture-02-renovacion-automatica.txt");
    }

    private static String readFixture(String name) throws IOException {
        Path path = Path.of("src/test/resources/golden-set-mock", name);
        return Files.readString(path);
    }

    @Test
    void returns200WithNonEmptyFindingsAndVerbatimQuotes_fixture01() throws Exception {
        assertHandlerProducesValidAnalysis("contracts/fixture-01.pdf", fixture01);
    }

    @Test
    void returns200WithNonEmptyFindingsAndVerbatimQuotes_fixture02() throws Exception {
        assertHandlerProducesValidAnalysis("contracts/fixture-02.pdf", fixture02);
    }

    private void assertHandlerProducesValidAnalysis(String objectKey, String fixtureText) throws Exception {
        AnalyzeContractHandler handler = new AnalyzeContractHandler(
                new FakeExtractTextInvoker(Map.of(objectKey, fixtureText)),
                new FakeDynamoDbClient(),
                new MockContractAnalysisService());

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(MAPPER.writeValueAsString(Map.of("objectKey", objectKey)));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);

        assertEquals(200, response.getStatusCode());

        JsonNode body = MAPPER.readTree(response.getBody());
        assertTrue(body.has("summary"));
        assertTrue(body.has("findings"));
        assertTrue(body.get("findings").isArray());
        assertFalse(body.get("findings").isEmpty(), "findings should be non-empty");

        for (JsonNode finding : body.get("findings")) {
            String clauseQuote = finding.get("clauseQuote").asText();
            assertTrue(fixtureText.contains(clauseQuote),
                    "clauseQuote must be a verbatim substring of the fixture text: " + clauseQuote);
        }
    }

    @Test
    void cacheHitOnSecondCallSkipsAnalysisServiceAndPopulatesCachedAt() throws Exception {
        String objectKey = "contracts/fixture-01.pdf";
        FakeDynamoDbClient sharedCache = new FakeDynamoDbClient();

        AnalyzeContractHandler handler1 = new AnalyzeContractHandler(
                new FakeExtractTextInvoker(Map.of(objectKey, fixture01)),
                sharedCache,
                new MockContractAnalysisService());

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(MAPPER.writeValueAsString(Map.of("objectKey", objectKey)));

        APIGatewayProxyResponseEvent first = handler1.handleRequest(event, null);
        assertEquals(200, first.getStatusCode());
        JsonNode firstBody = MAPPER.readTree(first.getBody());
        assertTrue(firstBody.get("cachedAt").isNull());

        // Second call, fresh handler instance sharing the same cache — should be served from cache.
        AnalyzeContractHandler handler2 = new AnalyzeContractHandler(
                new FakeExtractTextInvoker(Map.of(objectKey, fixture01)),
                sharedCache,
                new MockContractAnalysisService());
        APIGatewayProxyResponseEvent second = handler2.handleRequest(event, null);
        assertEquals(200, second.getStatusCode());
        JsonNode secondBody = MAPPER.readTree(second.getBody());
        assertFalse(secondBody.get("cachedAt").isNull(), "second call should be served from cache");
    }

    @Test
    void returns422WhenNoTextLayer() throws Exception {
        String objectKey = "contracts/scanned.pdf";
        AnalyzeContractHandler handler = new AnalyzeContractHandler(
                new FakeExtractTextInvoker(Map.of(), false),
                new FakeDynamoDbClient(),
                new MockContractAnalysisService());

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(MAPPER.writeValueAsString(Map.of("objectKey", objectKey)));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);
        assertEquals(422, response.getStatusCode());
    }

    @Test
    void returns422WhenTextTooShort() throws Exception {
        String objectKey = "contracts/tiny.pdf";
        AnalyzeContractHandler handler = new AnalyzeContractHandler(
                new FakeExtractTextInvoker(Map.of(objectKey, "muy corto")),
                new FakeDynamoDbClient(),
                new MockContractAnalysisService());

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(MAPPER.writeValueAsString(Map.of("objectKey", objectKey)));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);
        assertEquals(422, response.getStatusCode());
    }

    @Test
    void returns400WhenObjectKeyMissing() {
        AnalyzeContractHandler handler = new AnalyzeContractHandler(
                new FakeExtractTextInvoker(Map.of()),
                new FakeDynamoDbClient(),
                new MockContractAnalysisService());

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody("{}");

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);
        assertEquals(400, response.getStatusCode());
    }

    @Test
    void returns400WhenBodyMissing() {
        AnalyzeContractHandler handler = new AnalyzeContractHandler(
                new FakeExtractTextInvoker(Map.of()),
                new FakeDynamoDbClient(),
                new MockContractAnalysisService());

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);
        assertEquals(400, response.getStatusCode());
    }
}
