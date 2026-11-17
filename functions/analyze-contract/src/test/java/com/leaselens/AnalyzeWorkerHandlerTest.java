package com.leaselens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaselens.service.AnalysisJobStore;
import com.leaselens.service.LicenseStore;
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

    // ---- T18: a license use is spent only by a successful analysis ---------------------------

    @Test
    void successfulAnalysisSpendsOneUseAndRecordsTheBalance() {
        String objectKey = "contracts/fixture-01.pdf";
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        LicenseStore licenses = new LicenseStore(db, TABLE);
        licenses.create("LL-AAAA-BBBB", 300, "friend");

        new AnalyzeWorkerHandler(
                new FakeExtractTextInvoker(Map.of(objectKey, fixture01)), db, new MockContractAnalysisService())
                .handleRequest(Map.of("jobId", "job-1", "objectKey", objectKey, "licenseKey", "LL-AAAA-BBBB"), null);

        assertEquals(299, licenses.find("LL-AAAA-BBBB").remainingUses());
        AnalysisJobStore.Job job = new AnalysisJobStore(db, TABLE).get("job-1");
        assertEquals(AnalysisJobStore.Status.DONE, job.status());
        assertEquals(299, job.remainingUses());
    }

    @Test
    void cacheHitStillSpendsAUse() {
        String objectKey = "contracts/fixture-01.pdf";
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        LicenseStore licenses = new LicenseStore(db, TABLE);
        licenses.create("LL-AAAA-BBBB", 300, "friend");

        for (String jobId : new String[] {"job-1", "job-2"}) {
            new AnalyzeWorkerHandler(
                    new FakeExtractTextInvoker(Map.of(objectKey, fixture01)), db, new MockContractAnalysisService())
                    .handleRequest(Map.of("jobId", jobId, "objectKey", objectKey, "licenseKey", "LL-AAAA-BBBB"), null);
        }

        assertEquals(298, licenses.find("LL-AAAA-BBBB").remainingUses());
    }

    @Test
    void failedAnalysisDoesNotSpendAUse() {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        LicenseStore licenses = new LicenseStore(db, TABLE);
        licenses.create("LL-AAAA-BBBB", 300, "friend");

        new AnalyzeWorkerHandler(
                new FakeExtractTextInvoker(Map.of(), false), db, new MockContractAnalysisService())
                .handleRequest(Map.of("jobId", "job-1", "objectKey", "contracts/scanned.pdf",
                        "licenseKey", "LL-AAAA-BBBB"), null);

        assertFailedWith(db, 422);
        assertEquals(300, licenses.find("LL-AAAA-BBBB").remainingUses());
    }

    /** Two jobs can be accepted against the last use; only the first to finish may take it. */
    @Test
    void jobFinishingAfterTheLastUseWasTakenFailsWith403() {
        String objectKey = "contracts/fixture-01.pdf";
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        LicenseStore licenses = new LicenseStore(db, TABLE);
        licenses.create("LL-AAAA-BBBB", 1, "friend");

        for (String jobId : new String[] {"job-1", "job-2"}) {
            new AnalyzeWorkerHandler(
                    new FakeExtractTextInvoker(Map.of(objectKey, fixture01)), db, new MockContractAnalysisService())
                    .handleRequest(Map.of("jobId", jobId, "objectKey", objectKey, "licenseKey", "LL-AAAA-BBBB"), null);
        }

        AnalysisJobStore store = new AnalysisJobStore(db, TABLE);
        assertEquals(AnalysisJobStore.Status.DONE, store.get("job-1").status());
        assertEquals(AnalysisJobStore.Status.FAILED, store.get("job-2").status());
        assertEquals(403, store.get("job-2").errorStatusCode());
        assertNull(store.get("job-2").resultJson());
        assertEquals(0, licenses.find("LL-AAAA-BBBB").remainingUses());
    }

    // ---- per-key model override ---------------------------------------------------------------

    /** Records which model each call asked for, delegating the real work to the mock analyzer. */
    private static class ModelRecordingService extends MockContractAnalysisService {
        final java.util.List<String> models = new java.util.ArrayList<>();

        @Override
        public com.leaselens.model.AnalysisResult analyze(String contractText, String model) {
            models.add(model);
            return analyze(contractText);
        }
    }

    @Test
    void keyPinnedToAModelAnalyzesWithThatModelAndDoesNotShareTheDefaultCache() throws Exception {
        String objectKey = "contracts/fixture-01.pdf";
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        new LicenseStore(db, TABLE).create("DEF_AAAA", 10, "default", null);
        new LicenseStore(db, TABLE).create("CLH5_BBBB", 10, "haiku", "anthropic/claude-haiku-5.5");
        ModelRecordingService service = new ModelRecordingService();

        for (String[] run : new String[][] {{"job-1", "DEF_AAAA"}, {"job-2", "CLH5_BBBB"}, {"job-3", "CLH5_BBBB"}}) {
            new AnalyzeWorkerHandler(new FakeExtractTextInvoker(Map.of(objectKey, fixture01)), db, service)
                    .handleRequest(Map.of("jobId", run[0], "objectKey", objectKey, "licenseKey", run[1]), null);
        }

        // job-2 missed the default key's cache entry (own model, own entry); job-3 hit job-2's.
        assertEquals(java.util.Arrays.asList(null, "anthropic/claude-haiku-5.5"), service.models);
        AnalysisJobStore store = new AnalysisJobStore(db, TABLE);
        assertTrue(MAPPER.readTree(store.get("job-2").resultJson()).get("cachedAt").isNull());
        assertFalse(MAPPER.readTree(store.get("job-3").resultJson()).get("cachedAt").isNull());
    }

    private static void assertFailedWith(FakeDynamoDbClient db, int expectedStatusCode) {
        AnalysisJobStore.Job job = new AnalysisJobStore(db, TABLE).get("job-1");
        assertNotNull(job, "worker must record the job outcome");
        assertEquals(AnalysisJobStore.Status.FAILED, job.status());
        assertEquals(expectedStatusCode, job.errorStatusCode());
    }
}
