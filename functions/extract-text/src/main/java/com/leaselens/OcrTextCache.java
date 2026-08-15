package com.leaselens;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Caches OCR'd text by the S3 object's own ETag — not by the extracted text's content hash — so
 * repeated requests for the exact same uploaded object (a client retry after a 504, a re-analyze
 * click) reuse the same transcription instead of re-running the vision model, which is not fully
 * deterministic between calls on the same image (see T6's status note in the plan doc). This
 * restores the consistency the text-layer path already has "for free" (PDFBox is deterministic),
 * and as a side effect makes {@code analyze-contract}'s own content-hash cache reliable for
 * scanned documents too, since the text it hashes stops changing between requests for the same
 * object.
 *
 * <p>Shares the {@code leaselens-analyses} DynamoDB table with analyze-contract's final-result
 * cache, distinguished by an {@code "ocr#"}-prefixed partition key so the two item shapes never
 * collide (a raw SHA-256 hex string vs. an ETag never starts with {@code "ocr#"}).
 */
public class OcrTextCache {

    static final String KEY_PREFIX = "ocr#";
    // Shorter-lived than the final-analysis cache (30 days): the source S3 object itself expires
    // after 24h (the uploads bucket's lifecycle rule), so there's no point outliving it by
    // much — this only needs to survive client retries within the same upload's lifetime.
    private static final long TTL_HOURS = 48;

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public OcrTextCache(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    /** Returns the previously cached OCR text for this object's ETag, or {@code null} if absent/expired. */
    public String get(String eTag) {
        if (tableName == null || tableName.isBlank() || eTag == null || eTag.isBlank()) {
            return null;
        }
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("contentHash", AttributeValue.builder().s(cacheKey(eTag)).build()))
                        .build())
                .item();

        if (item == null || item.isEmpty()) {
            return null;
        }
        AttributeValue ttlAttr = item.get("ttl");
        AttributeValue textAttr = item.get("ocrText");
        if (ttlAttr == null || textAttr == null || textAttr.s() == null) {
            return null;
        }
        long ttlEpoch = Long.parseLong(ttlAttr.n());
        if (ttlEpoch <= Instant.now().getEpochSecond()) {
            return null;
        }
        return textAttr.s();
    }

    /** Stores OCR text for this object's ETag, so a retry of the same object skips the vision call. */
    public void put(String eTag, String ocrText) {
        if (tableName == null || tableName.isBlank() || eTag == null || eTag.isBlank()) {
            return;
        }
        long ttlEpoch = Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS).getEpochSecond();
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "contentHash", AttributeValue.builder().s(cacheKey(eTag)).build(),
                        "ttl", AttributeValue.builder().n(String.valueOf(ttlEpoch)).build(),
                        "ocrText", AttributeValue.builder().s(ocrText).build()))
                .build());
    }

    static String cacheKey(String eTag) {
        // S3 ETags are quoted in API responses (e.g. `"abc123"`); strip the quotes for a clean key.
        return KEY_PREFIX + eTag.replace("\"", "");
    }
}
