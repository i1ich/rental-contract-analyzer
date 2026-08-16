package com.leaselens.service;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks the state of an in-flight analysis so {@code POST /analyze} can answer immediately
 * instead of holding the HTTP connection open for the length of an LLM call.
 *
 * <p>This exists because API Gateway's REST integration timeout is a hard, unconfigurable 29
 * seconds, while a real analysis takes far longer — measured 2026-08-16 in production: ~83-92s on
 * {@code claude-sonnet-5}. Every cache-miss request used to 504 even though the Lambda finished
 * and cached the result seconds later. Swapping models does not fix it: {@code claude-haiku-4.5},
 * the fastest candidate that was quality-tested, still needed 29.5-38.6s and failed the T12 gate
 * on quote fidelity anyway.
 *
 * <p>Shares the {@code leaselens-analyses} DynamoDB table with the final-result cache and
 * extract-text's OCR cache, distinguished by a {@code "job#"}-prefixed partition key — the same
 * convention {@code OcrTextCache} uses for {@code "ocr#"}, and equally collision-free since a raw
 * SHA-256 hex string never starts with {@code "job#"}.
 */
public class AnalysisJobStore {

    static final String KEY_PREFIX = "job#";

    /**
     * Job records are transient progress state, not the analysis itself — the result is separately
     * cached for 30 days under its content hash. A day is far longer than any client will keep
     * polling and long enough that a user who leaves a tab open over lunch still gets an answer.
     */
    private static final int TTL_HOURS = 24;

    public enum Status { PENDING, DONE, FAILED }

    /** Immutable snapshot of a job record as read back from DynamoDB. */
    public record Job(Status status, String resultJson, String errorMessage, int errorStatusCode) {
        public boolean isPending() {
            return status == Status.PENDING;
        }
    }

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public AnalysisJobStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    /** Records a newly accepted job so the very first status poll finds something to report. */
    public void createPending(String jobId) {
        put(jobId, Status.PENDING, null, null, 0);
    }

    /** Stores the finished analysis, as already-serialized JSON. */
    public void markDone(String jobId, String resultJson) {
        put(jobId, Status.DONE, resultJson, null, 0);
    }

    /**
     * Records a terminal failure. {@code statusCode} is the HTTP status the synchronous endpoint
     * would have returned (422 for unusable text, 502 for an upstream LLM error, ...) so the
     * status endpoint can reproduce it and the frontend's existing error handling still applies.
     * {@code message} must never contain contract text — only what was already safe to return.
     */
    public void markFailed(String jobId, int statusCode, String message) {
        put(jobId, Status.FAILED, null, message, statusCode);
    }

    /** Returns the job, or {@code null} if it does not exist or has expired. */
    public Job get(String jobId) {
        requireTable();
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("contentHash", AttributeValue.builder().s(cacheKey(jobId)).build()))
                        .build())
                .item();

        if (item == null || item.isEmpty()) {
            return null;
        }
        AttributeValue statusAttr = item.get("jobStatus");
        AttributeValue ttlAttr = item.get("ttl");
        if (statusAttr == null || statusAttr.s() == null || ttlAttr == null) {
            return null;
        }
        // DynamoDB's TTL sweeper is best-effort and can lag by hours, so an expired item may still
        // be readable — treat it as gone rather than serving state the user was told had lapsed.
        if (Long.parseLong(ttlAttr.n()) <= Instant.now().getEpochSecond()) {
            return null;
        }

        AttributeValue resultAttr = item.get("resultJson");
        AttributeValue errorAttr = item.get("errorMessage");
        AttributeValue errorCodeAttr = item.get("errorStatusCode");
        return new Job(
                Status.valueOf(statusAttr.s()),
                resultAttr == null ? null : resultAttr.s(),
                errorAttr == null ? null : errorAttr.s(),
                errorCodeAttr == null ? 0 : Integer.parseInt(errorCodeAttr.n()));
    }

    private void put(String jobId, Status status, String resultJson, String errorMessage, int errorStatusCode) {
        requireTable();
        long ttlEpoch = Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS).getEpochSecond();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("contentHash", AttributeValue.builder().s(cacheKey(jobId)).build());
        item.put("ttl", AttributeValue.builder().n(String.valueOf(ttlEpoch)).build());
        item.put("jobStatus", AttributeValue.builder().s(status.name()).build());
        if (resultJson != null) {
            item.put("resultJson", AttributeValue.builder().s(resultJson).build());
        }
        if (errorMessage != null) {
            item.put("errorMessage", AttributeValue.builder().s(errorMessage).build());
        }
        if (errorStatusCode > 0) {
            item.put("errorStatusCode", AttributeValue.builder().n(String.valueOf(errorStatusCode)).build());
        }

        dynamoDb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    }

    private void requireTable() {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalStateException("TABLE_NAME environment variable is not set");
        }
    }

    static String cacheKey(String jobId) {
        return KEY_PREFIX + jobId;
    }
}
