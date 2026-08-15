package com.leaselens;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Offline tests for {@link OcrTextCache}, fully in-memory via {@link FakeDynamoDbClient} — no
 * real AWS calls.
 */
class OcrTextCacheTest {

    @Test
    void putThenGetRoundTripsTheSameText() {
        FakeDynamoDbClient dynamoDb = new FakeDynamoDbClient();
        OcrTextCache cache = new OcrTextCache(dynamoDb, "leaselens-analyses-test");

        cache.put("\"abc123\"", "texto reconocido");

        assertEquals("texto reconocido", cache.get("\"abc123\""));
    }

    @Test
    void stripsQuotesFromTheEtagSoTheSameObjectAlwaysMapsToTheSameKey() {
        FakeDynamoDbClient dynamoDb = new FakeDynamoDbClient();
        OcrTextCache cache = new OcrTextCache(dynamoDb, "leaselens-analyses-test");

        cache.put("\"abc123\"", "texto reconocido");

        // Whether the caller happens to pass the ETag with or without surrounding quotes
        // (S3's GetObjectResponse includes them; some callers might strip them first), the
        // same underlying object should hit the same cache entry.
        assertEquals("texto reconocido", cache.get("abc123"));
    }

    @Test
    void missingEntryReturnsNull() {
        FakeDynamoDbClient dynamoDb = new FakeDynamoDbClient();
        OcrTextCache cache = new OcrTextCache(dynamoDb, "leaselens-analyses-test");

        assertNull(cache.get("\"never-stored\""));
    }

    @Test
    void expiredEntryIsTreatedAsMissing() {
        FakeDynamoDbClient dynamoDb = new FakeDynamoDbClient();
        // Insert an already-expired item directly, bypassing put()'s TTL calculation.
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName("leaselens-analyses-test")
                .item(Map.of(
                        "contentHash", AttributeValue.builder().s(OcrTextCache.cacheKey("abc123")).build(),
                        "ttl", AttributeValue.builder().n(String.valueOf(Instant.now().getEpochSecond() - 60)).build(),
                        "ocrText", AttributeValue.builder().s("stale text").build()))
                .build());
        OcrTextCache cache = new OcrTextCache(dynamoDb, "leaselens-analyses-test");

        assertNull(cache.get("\"abc123\""));
    }

    @Test
    void blankOrNullTableNameNeverTouchesDynamoDb() {
        FakeDynamoDbClient dynamoDb = new FakeDynamoDbClient();
        OcrTextCache cache = new OcrTextCache(dynamoDb, null);

        cache.put("\"abc123\"", "texto");
        String result = cache.get("\"abc123\"");

        assertNull(result);
        assertEquals(0, dynamoDb.getItemCallCount());
        assertEquals(0, dynamoDb.putItemCallCount());
    }

    @Test
    void blankOrNullEtagIsANoOp() {
        FakeDynamoDbClient dynamoDb = new FakeDynamoDbClient();
        OcrTextCache cache = new OcrTextCache(dynamoDb, "leaselens-analyses-test");

        cache.put(null, "texto");
        cache.put("", "texto");

        assertNull(cache.get(null));
        assertNull(cache.get(""));
        assertEquals(0, dynamoDb.putItemCallCount());
    }

    @Test
    void cacheKeyPrefixesWithOcrAndStripsQuotes() {
        assertEquals("ocr#abc123", OcrTextCache.cacheKey("\"abc123\""));
        assertEquals("ocr#abc123", OcrTextCache.cacheKey("abc123"));
    }
}
