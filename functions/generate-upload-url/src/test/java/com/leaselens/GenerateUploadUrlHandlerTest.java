package com.leaselens;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaselens.service.DisclaimerGate;
import com.leaselens.service.LicenseStore;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link GenerateUploadUrlHandler} fully offline: {@link S3Presigner} signs locally,
 * so fake static credentials (never a real AWS account) are enough. Licenses live in an in-memory
 * fake of DynamoDB. The gate cases mirror {@code AnalyzeContractHandlerTest}'s.
 */
class GenerateUploadUrlHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TABLE = "leaselens-analyses-test";
    private static final String KEY = "LL-AAAA-BBBB";

    private static S3Presigner fakePresigner() {
        return S3Presigner.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .build();
    }

    private static GenerateUploadUrlHandler handler(FakeDynamoDbClient db, boolean licenseRequired) {
        return new GenerateUploadUrlHandler("test-bucket", fakePresigner(), db, licenseRequired);
    }

    private static APIGatewayProxyRequestEvent post(Map<String, Object> body) throws Exception {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(MAPPER.writeValueAsString(body));
        return event;
    }

    /** A body that passes both gates; tests remove or change one field at a time. */
    private static Map<String, Object> goodBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileSizeBytes", 1_000_000);
        body.put("licenseKey", KEY);
        body.put("disclaimerAccepted", true);
        body.put("disclaimerVersion", DisclaimerGate.CURRENT_VERSION);
        return body;
    }

    private static FakeDynamoDbClient dbWithKey() {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        new LicenseStore(db, TABLE).create(KEY, 300, "friend");
        return db;
    }

    private static void assertNoUrlIssued(APIGatewayProxyResponseEvent response, int status) throws Exception {
        assertEquals(status, response.getStatusCode());
        JsonNode body = MAPPER.readTree(response.getBody());
        assertFalse(body.has("uploadUrl"), "a refused request must not receive a presigned URL");
        assertFalse(body.has("objectKey"));
    }

    /** Flips the stored record to revoked, the way an operator would with a DynamoDB update. */
    private static void revoke(FakeDynamoDbClient db) {
        Map<String, AttributeValue> item = new HashMap<>(db.getItem(GetItemRequest.builder()
                .key(Map.of("contentHash", AttributeValue.builder().s("license#" + KEY).build()))
                .build()).item());
        item.put("status", AttributeValue.builder().s("revoked").build());
        db.putItem(PutItemRequest.builder().item(item).build());
    }

    // ---- success and existing behaviour -----------------------------------------------------

    @Test
    void returns200WithUploadUrlAndObjectKey() throws Exception {
        APIGatewayProxyResponseEvent response = handler(dbWithKey(), true).handleRequest(post(goodBody()), null);

        assertEquals(200, response.getStatusCode());
        JsonNode body = MAPPER.readTree(response.getBody());
        assertTrue(body.get("uploadUrl").asText().startsWith("https://"));
        assertTrue(body.get("objectKey").asText().startsWith("uploads/"));
        assertEquals("application/pdf", body.get("requiredContentType").asText());
        assertEquals(900, body.get("expiresInSeconds").asLong());
    }

    @Test
    void keyLookupIgnoresCaseAndWhitespaceLikeAnalyze() throws Exception {
        Map<String, Object> body = goodBody();
        body.put("licenseKey", "  ll-aaaa-bbbb ");

        assertEquals(200, handler(dbWithKey(), true).handleRequest(post(body), null).getStatusCode());
    }

    @Test
    void issuingAnUploadUrlNeverChargesAUse() throws Exception {
        FakeDynamoDbClient db = dbWithKey();

        handler(db, true).handleRequest(post(goodBody()), null);

        assertEquals(300, new LicenseStore(db, TABLE).find(KEY).remainingUses());
    }

    @Test
    void returns400WhenDeclaredFileSizeExceedsCap() throws Exception {
        Map<String, Object> body = goodBody();
        body.put("fileSizeBytes", GenerateUploadUrlHandler.MAX_UPLOAD_SIZE_BYTES + 1);

        assertNoUrlIssued(handler(dbWithKey(), true).handleRequest(post(body), null), 400);
    }

    @Test
    void returns200WhenFileSizeIsOmitted() throws Exception {
        Map<String, Object> body = goodBody();
        body.remove("fileSizeBytes");

        assertEquals(200, handler(dbWithKey(), true).handleRequest(post(body), null).getStatusCode());
    }

    @Test
    void returns500WhenBucketNameNotConfigured() throws Exception {
        GenerateUploadUrlHandler handler =
                new GenerateUploadUrlHandler(null, fakePresigner(), dbWithKey(), true);

        assertEquals(500, handler.handleRequest(post(goodBody()), null).getStatusCode());
    }

    // ---- license gate -----------------------------------------------------------------------

    @Test
    void noKeyIsRefusedWith401() throws Exception {
        Map<String, Object> body = goodBody();
        body.remove("licenseKey");

        assertNoUrlIssued(handler(dbWithKey(), true).handleRequest(post(body), null), 401);
    }

    @Test
    void blankKeyIsRefusedWith401() throws Exception {
        Map<String, Object> body = goodBody();
        body.put("licenseKey", "   ");

        assertNoUrlIssued(handler(dbWithKey(), true).handleRequest(post(body), null), 401);
    }

    @Test
    void unknownKeyIsRefusedWith403() throws Exception {
        Map<String, Object> body = goodBody();
        body.put("licenseKey", "LL-NOPE");

        assertNoUrlIssued(handler(dbWithKey(), true).handleRequest(post(body), null), 403);
    }

    @Test
    void revokedKeyIsRefusedWith403() throws Exception {
        FakeDynamoDbClient db = dbWithKey();
        revoke(db);

        assertNoUrlIssued(handler(db, true).handleRequest(post(goodBody()), null), 403);
    }

    @Test
    void exhaustedKeyIsRefusedWith403() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        LicenseStore store = new LicenseStore(db, TABLE);
        store.create(KEY, 1, "friend");
        store.consumeOne(KEY);

        assertNoUrlIssued(handler(db, true).handleRequest(post(goodBody()), null), 403);
    }

    @Test
    void expiredKeyIsRefusedWith403AndTheExpiredCode() throws Exception {
        FakeDynamoDbClient db = new FakeDynamoDbClient();
        new LicenseStore(db, TABLE).create(KEY, 300, "friend", null, Instant.now().minus(1, ChronoUnit.DAYS));

        APIGatewayProxyResponseEvent response = handler(db, true).handleRequest(post(goodBody()), null);

        assertNoUrlIssued(response, 403);
        assertEquals("LICENSE_EXPIRED", MAPPER.readTree(response.getBody()).get("code").asText());
    }

    // ---- disclaimer gate --------------------------------------------------------------------

    @Test
    void missingDisclaimerFieldIsRefusedWithTheCurrentVersion() throws Exception {
        Map<String, Object> body = goodBody();
        body.remove("disclaimerAccepted");
        body.remove("disclaimerVersion");

        APIGatewayProxyResponseEvent response = handler(dbWithKey(), true).handleRequest(post(body), null);

        assertNoUrlIssued(response, 400);
        JsonNode json = MAPPER.readTree(response.getBody());
        assertEquals("DISCLAIMER_NOT_ACCEPTED", json.get("code").asText());
        assertEquals(DisclaimerGate.CURRENT_VERSION, json.get("currentVersion").asText());
    }

    @Test
    void disclaimerAcceptedFalseIsRefused() throws Exception {
        Map<String, Object> body = goodBody();
        body.put("disclaimerAccepted", false);

        assertNoUrlIssued(handler(dbWithKey(), true).handleRequest(post(body), null), 400);
    }

    @Test
    void staleDisclaimerVersionIsRefused() throws Exception {
        Map<String, Object> body = goodBody();
        body.put("disclaimerVersion", "1999-01-01.0");

        assertNoUrlIssued(handler(dbWithKey(), true).handleRequest(post(body), null), 400);
    }

    @Test
    void disclaimerAcceptedAsAStringIsRefused() throws Exception {
        Map<String, Object> body = goodBody();
        body.put("disclaimerAccepted", "true");

        assertNoUrlIssued(handler(dbWithKey(), true).handleRequest(post(body), null), 400);
    }

    @Test
    void disclaimerGateAppliesEvenWhenLicensesAreNotRequired() throws Exception {
        Map<String, Object> body = goodBody();
        body.remove("disclaimerAccepted");

        assertNoUrlIssued(handler(new FakeDynamoDbClient(), false).handleRequest(post(body), null), 400);
    }

    @Test
    void disclaimerIsCheckedBeforeTheLicenseSoARefusedRequestReadsNoStore() throws Exception {
        // Both are bad: the disclaimer answer (400) must win, and the license must not be read.
        AtomicInteger reads = new AtomicInteger();
        FakeDynamoDbClient db = new FakeDynamoDbClient() {
            @Override
            public GetItemResponse getItem(GetItemRequest request) {
                reads.incrementAndGet();
                return super.getItem(request);
            }
        };
        Map<String, Object> body = goodBody();
        body.put("licenseKey", "LL-NOPE");
        body.put("disclaimerAccepted", false);

        assertNoUrlIssued(handler(db, true).handleRequest(post(body), null), 400);
        assertEquals(0, reads.get());
    }

    // ---- the bypass cases: a caller that never saw the UI -----------------------------------

    @Test
    void emptyBodyDirectApiCallIsRefused() throws Exception {
        assertNoUrlIssued(handler(dbWithKey(), true).handleRequest(new APIGatewayProxyRequestEvent(), null), 400);
    }

    @Test
    void garbageBodyDirectApiCallIsRefused() throws Exception {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody("not json");

        assertNoUrlIssued(handler(dbWithKey(), true).handleRequest(event, null), 400);
    }

    @Test
    void sizeOnlyBodyLikeTheOldClientIsRefused() throws Exception {
        assertNoUrlIssued(handler(dbWithKey(), true)
                .handleRequest(post(Map.of("fileSizeBytes", 1000)), null), 400);
    }

    @Test
    void validKeyButNoDisclaimerDirectApiCallIsRefused() throws Exception {
        assertNoUrlIssued(handler(dbWithKey(), true)
                .handleRequest(post(Map.of("licenseKey", KEY)), null), 400);
    }

    @Test
    void disclaimerButNoKeyDirectApiCallIsRefused() throws Exception {
        assertNoUrlIssued(handler(dbWithKey(), true).handleRequest(post(Map.of(
                "disclaimerAccepted", true,
                "disclaimerVersion", DisclaimerGate.CURRENT_VERSION)), null), 401);
    }
}
