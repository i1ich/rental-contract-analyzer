package com.leaselens;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaselens.service.AnalysisJobStore;
import com.leaselens.service.DisclaimerGate;
import com.leaselens.service.AnalysisWorkerInvoker;
import com.leaselens.service.LicenseStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the public HTTP surface of the asynchronous analysis flow: accepting a job, and the
 * three states a status poll can report. The analysis pipeline itself is covered by
 * {@code AnalyzeWorkerHandlerTest} — this handler deliberately does none of that work.
 */
class AnalyzeContractHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TABLE = "leaselens-analyses-test";

    /** Records what the handler asked for without running any analysis. */
    private static class RecordingWorkerInvoker implements AnalysisWorkerInvoker {
        final List<String> startedJobIds = new ArrayList<>();
        final List<String> startedObjectKeys = new ArrayList<>();
        final List<String> startedLicenseKeys = new ArrayList<>();

        @Override
        public void startAnalysis(String jobId, String objectKey, String licenseKey) {
            startedJobIds.add(jobId);
            startedObjectKeys.add(objectKey);
            startedLicenseKeys.add(licenseKey);
        }
    }

    private static APIGatewayProxyRequestEvent postWith(String bodyJson) {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("POST");
        event.setBody(bodyJson);
        return event;
    }

    /** A well-formed request body that carries the disclaimer acceptance. */
    private static String acceptedBody() throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "objectKey", "contracts/fixture-01.pdf",
                "disclaimerAccepted", true,
                "disclaimerVersion", DisclaimerGate.CURRENT_VERSION));
    }

    private static APIGatewayProxyRequestEvent getStatusOf(String jobId) {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("GET");
        event.setPathParameters(Map.of("jobId", jobId));
        return event;
    }

    @Test
    void postAcceptsTheJobAndReturns202WithAJobIdWithoutAnalyzing() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();
        AnalyzeContractHandler handler = new AnalyzeContractHandler(db, worker);

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                postWith(acceptedBody()), null);

        // 202, not 200: the analysis has been accepted, not performed. This is the whole point of
        // the redesign — a real analysis takes ~83-92s against API Gateway's hard 29s cap.
        assertEquals(202, response.getStatusCode());
        JsonNode body = MAPPER.readTree(response.getBody());
        assertEquals("pending", body.get("status").asText());
        String jobId = body.get("jobId").asText();
        assertFalse(jobId.isBlank());

        assertEquals(List.of(jobId), worker.startedJobIds, "the worker must be started for this job");
        assertEquals(List.of("contracts/fixture-01.pdf"), worker.startedObjectKeys);
    }

    /**
     * A client can poll before the worker has done anything, so accepting a job has to leave a
     * record behind immediately — otherwise the first poll 404s and the client reasonably
     * concludes the job never existed.
     */
    @Test
    void statusIsPendingImmediatelyAfterAccepting() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        AnalyzeContractHandler handler = new AnalyzeContractHandler(db, new RecordingWorkerInvoker());

        APIGatewayProxyResponseEvent accepted = handler.handleRequest(
                postWith(acceptedBody()), null);
        String jobId = MAPPER.readTree(accepted.getBody()).get("jobId").asText();

        APIGatewayProxyResponseEvent status = handler.handleRequest(getStatusOf(jobId), null);
        assertEquals(200, status.getStatusCode());
        assertEquals("pending", MAPPER.readTree(status.getBody()).get("status").asText());
    }

    @Test
    void statusReturnsTheResultOnceTheWorkerIsDone() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        new AnalysisJobStore(db, TABLE).markDone("job-1",
                "{\"summary\":\"resumen\",\"findings\":[],\"cachedAt\":null}");

        APIGatewayProxyResponseEvent status =
                new AnalyzeContractHandler(db, new RecordingWorkerInvoker()).handleRequest(getStatusOf("job-1"), null);

        assertEquals(200, status.getStatusCode());
        JsonNode body = MAPPER.readTree(status.getBody());
        assertEquals("done", body.get("status").asText());
        assertNotNull(body.get("result"));
        assertEquals("resumen", body.get("result").get("summary").asText());
    }

    /**
     * A failed analysis is a successful poll: the 4xx/5xx the synchronous endpoint would have
     * returned travels in the body, so the client can tell "the analysis failed" apart from "the
     * status request failed".
     */
    @Test
    void statusReportsAFailedAnalysisAsA200CarryingTheOriginalStatusCode() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        new AnalysisJobStore(db, TABLE).markFailed("job-1", 422, "No pudimos extraer suficiente texto");

        APIGatewayProxyResponseEvent status =
                new AnalyzeContractHandler(db, new RecordingWorkerInvoker()).handleRequest(getStatusOf("job-1"), null);

        assertEquals(200, status.getStatusCode());
        JsonNode body = MAPPER.readTree(status.getBody());
        assertEquals("error", body.get("status").asText());
        assertEquals(422, body.get("errorStatusCode").asInt());
        assertTrue(body.get("error").asText().contains("No pudimos extraer"));
    }

    @Test
    void statusReturns404ForAnUnknownJob() {
        APIGatewayProxyResponseEvent status = new AnalyzeContractHandler(
                new FakeDynamoDbClient(), new RecordingWorkerInvoker())
                .handleRequest(getStatusOf("does-not-exist"), null);

        assertEquals(404, status.getStatusCode());
    }

    @Test
    void returns400WhenObjectKeyMissing() {
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();
        APIGatewayProxyResponseEvent response =
                new AnalyzeContractHandler(new FakeDynamoDbClient(), worker).handleRequest(postWith("{}"), null);

        assertEquals(400, response.getStatusCode());
        assertTrue(worker.startedJobIds.isEmpty(), "no worker should be started for an invalid request");
    }

    // ---- T18 license gate -------------------------------------------------------------------

    private static APIGatewayProxyRequestEvent postWithLicense(String licenseKey) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("objectKey", "contracts/fixture-01.pdf");
        body.put("disclaimerAccepted", true);
        body.put("disclaimerVersion", DisclaimerGate.CURRENT_VERSION);
        if (licenseKey != null) {
            body.put("licenseKey", licenseKey);
        }
        return postWith(MAPPER.writeValueAsString(body));
    }

    private static AnalyzeContractHandler licensedHandler(FakeDynamoDbClient db, RecordingWorkerInvoker worker) {
        return new AnalyzeContractHandler(db, worker, true);
    }

    @Test
    void validKeyStartsTheJobAndIsHandedToTheWorkerWithoutBeingCharged() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        new LicenseStore(db, TABLE).create("LL-AAAA-BBBB", 300, "friend");
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();

        // Typed sloppily on purpose: the key is normalized before lookup and before the handoff.
        APIGatewayProxyResponseEvent response =
                licensedHandler(db, worker).handleRequest(postWithLicense("  ll-aaaa-bbbb "), null);

        assertEquals(202, response.getStatusCode());
        assertEquals(List.of("LL-AAAA-BBBB"), worker.startedLicenseKeys);
        assertEquals(300, new LicenseStore(db, TABLE).find("LL-AAAA-BBBB").remainingUses(),
                "accepting a job must not spend a use — only a finished analysis does");
    }

    @Test
    void missingKeyIsRejectedWith401AndNothingStarts() throws Exception {
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();
        APIGatewayProxyResponseEvent response =
                licensedHandler(new FakeDynamoDbClient(), worker).handleRequest(postWithLicense(null), null);

        assertEquals(401, response.getStatusCode());
        assertTrue(worker.startedJobIds.isEmpty());
    }

    @Test
    void unknownKeyIsRejectedWith403() throws Exception {
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();
        APIGatewayProxyResponseEvent response =
                licensedHandler(new FakeDynamoDbClient(), worker).handleRequest(postWithLicense("LL-NOPE"), null);

        assertEquals(403, response.getStatusCode());
        assertTrue(worker.startedJobIds.isEmpty());
    }

    @Test
    void exhaustedKeyIsRejectedWith403() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        LicenseStore licenses = new LicenseStore(db, TABLE);
        licenses.create("LL-AAAA-BBBB", 1, "friend");
        licenses.consumeOne("LL-AAAA-BBBB");
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();

        APIGatewayProxyResponseEvent response =
                licensedHandler(db, worker).handleRequest(postWithLicense("LL-AAAA-BBBB"), null);

        assertEquals(403, response.getStatusCode());
        assertTrue(response.getBody().contains("todos sus análisis"));
        assertTrue(worker.startedJobIds.isEmpty());
    }

    @Test
    void keyIsNotRequiredWhenLicensingIsOff() throws Exception {
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();
        APIGatewayProxyResponseEvent response =
                new AnalyzeContractHandler(new FakeDynamoDbClient(), worker, false)
                        .handleRequest(postWithLicense(null), null);

        assertEquals(202, response.getStatusCode());
        assertEquals(java.util.Collections.singletonList(null), worker.startedLicenseKeys);
    }

    @Test
    void statusIncludesRemainingUsesWhenTheJobWasCharged() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        new AnalysisJobStore(db, TABLE).markDone("job-1",
                "{\"summary\":\"resumen\",\"findings\":[],\"cachedAt\":null}", 283);

        APIGatewayProxyResponseEvent status = new AnalyzeContractHandler(db, new RecordingWorkerInvoker())
                .handleRequest(getStatusOf("job-1"), null);

        assertEquals(283, MAPPER.readTree(status.getBody()).get("remainingUses").asInt());
    }

    // ---- Disclaimer gate --------------------------------------------------------------------

    private static APIGatewayProxyRequestEvent postWithDisclaimer(Object accepted, String version) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("objectKey", "contracts/fixture-01.pdf");
        if (accepted != null) {
            body.put("disclaimerAccepted", accepted);
        }
        if (version != null) {
            body.put("disclaimerVersion", version);
        }
        return postWith(MAPPER.writeValueAsString(body));
    }

    private void assertRefusedForDisclaimer(APIGatewayProxyResponseEvent response, RecordingWorkerInvoker worker,
                                            FakeDynamoDbClient db) throws Exception {
        assertEquals(400, response.getStatusCode());
        JsonNode body = MAPPER.readTree(response.getBody());
        assertEquals("DISCLAIMER_NOT_ACCEPTED", body.get("code").asText());
        assertEquals(DisclaimerGate.CURRENT_VERSION, body.get("currentVersion").asText());
        assertTrue(worker.startedJobIds.isEmpty(), "no job (and so no OCR / LLM call) without acceptance");
        assertTrue(db.getItemKeysForTest().isEmpty(), "no job record may be written for a refused request");
    }

    @Test
    void requestWithoutTheAcceptanceFieldsIsRefused() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();
        // The bypass case: a direct API call (curl, script) that never saw the UI.
        APIGatewayProxyResponseEvent response = new AnalyzeContractHandler(db, worker, false)
                .handleRequest(postWithDisclaimer(null, null), null);
        assertRefusedForDisclaimer(response, worker, db);
    }

    @Test
    void explicitFalseIsRefused() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();
        APIGatewayProxyResponseEvent response = new AnalyzeContractHandler(db, worker, false)
                .handleRequest(postWithDisclaimer(false, DisclaimerGate.CURRENT_VERSION), null);
        assertRefusedForDisclaimer(response, worker, db);
    }

    @Test
    void acceptanceWithoutAVersionIsRefused() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();
        APIGatewayProxyResponseEvent response = new AnalyzeContractHandler(db, worker, false)
                .handleRequest(postWithDisclaimer(true, null), null);
        assertRefusedForDisclaimer(response, worker, db);
    }

    @Test
    void outdatedVersionIsRefusedAndTheBodyNamesTheCurrentOne() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();
        APIGatewayProxyResponseEvent response = new AnalyzeContractHandler(db, worker, false)
                .handleRequest(postWithDisclaimer(true, "1999-01-01.0"), null);
        assertRefusedForDisclaimer(response, worker, db);
    }

    @Test
    void stringTrueIsNotAnAcceptance() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();
        // Jackson would coerce "true" to a Boolean; a boolean-typed contract is what we promise.
        // Whatever it does, a non-boolean must never slip past as an acceptance by accident.
        APIGatewayProxyResponseEvent response = new AnalyzeContractHandler(db, worker, false)
                .handleRequest(postWithDisclaimer("no", DisclaimerGate.CURRENT_VERSION), null);
        assertEquals(400, response.getStatusCode());
        assertTrue(worker.startedJobIds.isEmpty());
    }

    @Test
    void disclaimerIsCheckedBeforeTheLicenseSoARefusedRequestTouchesNothing() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        new LicenseStore(db, TABLE).create("LL-AAAA-BBBB", 300, "friend");
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("objectKey", "contracts/fixture-01.pdf");
        body.put("licenseKey", "LL-AAAA-BBBB");

        APIGatewayProxyResponseEvent response = licensedHandler(db, worker)
                .handleRequest(postWith(MAPPER.writeValueAsString(body)), null);

        assertEquals(400, response.getStatusCode());
        assertTrue(worker.startedJobIds.isEmpty());
        assertEquals(300, new LicenseStore(db, TABLE).find("LL-AAAA-BBBB").remainingUses());
    }

    @Test
    void currentVersionAcceptanceTakesTheNormalFlow() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();
        APIGatewayProxyResponseEvent response = new AnalyzeContractHandler(db, worker, false)
                .handleRequest(postWithDisclaimer(true, DisclaimerGate.CURRENT_VERSION), null);

        assertEquals(202, response.getStatusCode());
        assertEquals(1, worker.startedJobIds.size());
    }

    @Test
    void returns400WhenBodyMissing() {
        RecordingWorkerInvoker worker = new RecordingWorkerInvoker();
        APIGatewayProxyResponseEvent response =
                new AnalyzeContractHandler(new FakeDynamoDbClient(), worker).handleRequest(postWith(null), null);

        assertEquals(400, response.getStatusCode());
        assertTrue(worker.startedJobIds.isEmpty(), "no worker should be started for an invalid request");
    }
}
