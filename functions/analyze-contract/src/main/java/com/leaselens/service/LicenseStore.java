package com.leaselens.service;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Usage-limited license keys (T18): a key grants a fixed number of completed analyses.
 *
 * <p>Shares the {@code leaselens-analyses} DynamoDB table with the result cache, job records and
 * OCR cache, distinguished by a {@code "license#"}-prefixed partition key — the same convention as
 * {@code "job#"} and {@code "ocr#"}. Unlike those, license records carry no {@code ttl}: a key
 * expires by running out of uses, never by the clock.
 *
 * <p>Checking and charging are deliberately separate. {@link #find} is a cheap read used to turn
 * away bad keys before any OCR/LLM money is spent; {@link #consumeOne} is the only thing that
 * decrements, and the worker calls it only once an analysis has actually succeeded — so a failed
 * analysis never costs the user a use.
 */
public class LicenseStore {

    static final String KEY_PREFIX = "license#";

    public enum Status { ACTIVE, EXHAUSTED, REVOKED }

    /** Immutable snapshot of a license record. */
    public record License(int totalUses, int remainingUses, Status status) {
        /** A key can start a new analysis only if it is active and has uses left. */
        public boolean isUsable() {
            return status == Status.ACTIVE && remainingUses > 0;
        }
    }

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public LicenseStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    /** Keys are generated upper-case; be forgiving about whitespace and case when users retype them. */
    public static String normalize(String key) {
        return key == null ? null : key.trim().toUpperCase(Locale.ROOT);
    }

    /** Returns the license, or {@code null} if no such key exists. */
    public License find(String key) {
        requireTable();
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(partitionKey(key))
                        .build())
                .item();
        if (item == null || item.isEmpty()
                || item.get("remainingUses") == null || item.get("totalUses") == null) {
            return null;
        }
        AttributeValue statusAttr = item.get("status");
        Status status = statusAttr == null || statusAttr.s() == null
                ? Status.ACTIVE : Status.valueOf(statusAttr.s().toUpperCase(Locale.ROOT));
        return new License(
                Integer.parseInt(item.get("totalUses").n()),
                Integer.parseInt(item.get("remainingUses").n()),
                status);
    }

    /**
     * Atomically spends one use. Returns the number of uses left afterwards, or {@code -1} if the
     * key had none to spend (exhausted, revoked, or unknown). The condition lives in DynamoDB, not
     * in a read-then-write here, so two analyses finishing at once can never take the count below
     * zero.
     */
    public int consumeOne(String key) {
        requireTable();
        try {
            var response = dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(partitionKey(key))
                    .updateExpression("SET remainingUses = remainingUses - :one, lastUsedAt = :now")
                    .conditionExpression("remainingUses > :zero AND #st = :active")
                    .expressionAttributeNames(Map.of("#st", "status"))
                    .expressionAttributeValues(Map.of(
                            ":one", number(1),
                            ":zero", number(0),
                            ":active", AttributeValue.builder().s("active").build(),
                            ":now", AttributeValue.builder().s(Instant.now().toString()).build()))
                    .returnValues(ReturnValue.UPDATED_NEW)
                    .build());
            return Integer.parseInt(response.attributes().get("remainingUses").n());
        } catch (ConditionalCheckFailedException e) {
            return -1;
        }
    }

    /** Creates a new active license. Fails if the key already exists, so a key is never overwritten. */
    public void create(String key, int totalUses, String ownerLabel) {
        requireTable();
        Map<String, AttributeValue> item = new HashMap<>(partitionKey(key));
        item.put("totalUses", number(totalUses));
        item.put("remainingUses", number(totalUses));
        item.put("status", AttributeValue.builder().s("active").build());
        item.put("ownerLabel", AttributeValue.builder().s(ownerLabel == null ? "" : ownerLabel).build());
        item.put("createdAt", AttributeValue.builder().s(Instant.now().toString()).build());
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .conditionExpression("attribute_not_exists(contentHash)")
                .build());
    }

    private static Map<String, AttributeValue> partitionKey(String key) {
        return Map.of("contentHash", AttributeValue.builder().s(KEY_PREFIX + normalize(key)).build());
    }

    private static AttributeValue number(int value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }

    private void requireTable() {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalStateException("TABLE_NAME environment variable is not set");
        }
    }
}
