package com.leaselens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaselens.service.AnalysisJobStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the background analysis pipeline (extract-text seam, guards, cache, job bookkeeping)
 * using fakes for every AWS-touching dependency, so it runs fully offline.
 *
 * <p>These assertions largely moved here from {@code AnalyzeContractHandlerTest} when analysis
 * became asynchronous — the pipeline is the same, but its outcome is now written to a job record
 * rather than returned as an HTTP response, so what used to be a status-code assertion is now an
 * assertion about the stored job.
 *
 * <p>The fixtures are fictional test data (see the disclaimer header in each fixture file) — not
 * real contracts and not the T2/T7 golden set.
 */
class AnalyzeWorkerHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TABLE = "leaselens-analyses-test";
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
    void storesAnalysisWithVerbatimQuotes_fixture01() throws Exception {
        assertWorkerProducesValidAnalysis("contracts/fixture-01.pdf", fixture01);
    }

    @Test
    void storesAnalysisWithVerbatimQuotes_fixture02() throws Exception {
        assertWorkerProducesValidAnalysis("contracts/fixture-02.pdf", fixture02);
    }

    private void assertWorkerProducesValidAnalysis(String objectKey, String fixtureText) throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        AnalyzeWorkerHandler worker = new AnalyzeWorkerHandler(
                new FakeExtractTextInvoker(Map.of(objectKey, fixtureText)),
                db,
                new MockContractAnalysisService());

        worker.handleRequest(Map.of("jobId", "job-1", "objectKey", objectKey), null);

        AnalysisJobStore.Job job = new AnalysisJobStore(db, TABLE).get("job-1");
        assertNotNull(job, "worker must record the job outcome");
        assertEquals(AnalysisJobStore.Status.DONE, job.status());

        JsonNode result = MAPPER.readTree(job.resultJson());
        assertTrue(result.has("summary"));
        assertTrue(result.get("findings").isArray());
        assertFalse(result.get("findings").isEmpty(), "findings should be non-empty");

        for (JsonNode finding : result.get("findings")) {
            String clauseQuote = finding.get("clauseQuote").asText();
            assertTrue(fixtureText.contains(clauseQuote),
                    "clauseQuote must be a verbatim substring of the fixture text: " + clauseQuote);
        }
    }

    @Test
    void cacheHitOnSecondRunSkipsAnalysisServiceAndPopulatesCachedAt() throws Exception {
        String objectKey = "contracts/fixture-01.pdf";
        FakeDynamoDbClient sharedCache = new FakeDynamoDbClient();

        new AnalyzeWorkerHandler(
                new FakeExtractTextInvoker(Map.of(objectKey, fixture01)),
                sharedCache,
                new MockContractAnalysisService())
                .handleRequest(Map.of("jobId", "job-1", "objectKey", objectKey), null);

        AnalysisJobStore store = new AnalysisJobStore(sharedCache, TABLE);
        assertTrue(MAPPER.readTree(store.get("job-1").resultJson()).get("cachedAt").isNull());

        // Second run, fresh handler instance sharing the same table — should be served from cache.
        new AnalyzeWorkerHandler(
                new FakeExtractTextInvoker(Map.of(objectKey, fixture01)),
                sharedCache,
                new MockContractAnalysisService())
                .handleRequest(Map.of("jobId", "job-2", "objectKey", objectKey), null);

        assertFalse(MAPPER.readTree(store.get("job-2").resultJson()).get("cachedAt").isNull(),
                "second run should be served from cache");
    }

    @Test
    void recordsA422WhenNoTextLayer() {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        new AnalyzeWorkerHandler(
                new FakeExtractTextInvoker(Map.of(), false),
                db,
                new MockContractAnalysisService())
                .handleRequest(Map.of("jobId", "job-1", "objectKey", "contracts/scanned.pdf"), null);

        assertFailedWith(db, 422);
    }

    @Test
    void recordsA422WhenTextTooShort() {
        String objectKey = "contracts/tiny.pdf";
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        new AnalyzeWorkerHandler(
                new FakeExtractTextInvoker(Map.of(objectKey, "muy corto")),
                db,
                new MockContractAnalysisService())
                .handleRequest(Map.of("jobId", "job-1", "objectKey", objectKey), null);

        assertFailedWith(db, 422);
    }

    @Test
    void recordsA400WhenObjectKeyMissing() {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        new AnalyzeWorkerHandler(
                new FakeExtractTextInvoker(Map.of()),
                db,
                new MockContractAnalysisService())
                .handleRequest(Map.of("jobId", "job-1"), null);

        assertFailedWith(db, 400);
    }

    /**
     * The failure path matters more here than it did synchronously: nothing is listening to an
     * EVENT invocation's return value, so a failure the worker doesn't write down leaves the
     * client polling PENDING until the job's TTL expires.
     */
    @Test
    void recordsAFailureWhenTheAnalysisServiceThrows() {
        String objectKey = "contracts/fixture-01.pdf";
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        new AnalyzeWorkerHandler(
                new FakeExtractTextInvoker(Map.of(objectKey, fixture01)),
                db,
                contractText -> {
                    throw new RuntimeException("upstream exploded");
                })
                .handleRequest(Map.of("jobId", "job-1", "objectKey", objectKey), null);

        AnalysisJobStore.Job job = new AnalysisJobStore(db, TABLE).get("job-1");
        assertNotNull(job, "a crash must still leave a terminal job record behind");
        assertEquals(AnalysisJobStore.Status.FAILED, job.status());
        assertEquals(500, job.errorStatusCode());
        // The user-facing message must not carry the internal failure detail.
        assertFalse(job.errorMessage().contains("upstream exploded"));
        assertNull(job.resultJson());
    }

    private static void assertFailedWith(FakeDynamoDbClient db, int expectedStatusCode) {
        AnalysisJobStore.Job job = new AnalysisJobStore(db, TABLE).get("job-1");
        assertNotNull(job, "worker must record the job outcome");
        assertEquals(AnalysisJobStore.Status.FAILED, job.status());
        assertEquals(expectedStatusCode, job.errorStatusCode());
    }
}
