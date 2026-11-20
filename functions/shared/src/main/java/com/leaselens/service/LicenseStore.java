package com.leaselens.service;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Usage-limited license keys (T18): a key grants a fixed number of completed analyses.
 *
 * <p>Shares the {@code leaselens-analyses} DynamoDB table with the result cache, job records and
 * OCR cache, distinguished by a {@code "license#"}-prefixed partition key — the same convention as
 * {@code "job#"} and {@code "ocr#"}. Unlike those, license records carry no DynamoDB {@code ttl}
 * (the record must outlive the key so a status check can say "expired"). A key stops working by
 * running out of uses, or at its {@code expiresAt} (the next 15th after creation), which is
 * checked on every read rather than by any scheduled job.
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
    public record License(int totalUses, int remainingUses, Status status, String model, Instant expiresAt) {
        /**
         * True once {@code expiresAt} has been reached. A record with no {@code expiresAt} (keys
         * issued before monthly expiry existed) never expires on its own; give it one with the
         * backfill command in docs/DEPLOYMENT.md.
         */
        public boolean isExpired(Instant now) {
            return expiresAt != null && !now.isBefore(expiresAt);
        }

        /** A key can start a new analysis only if it is active, unexpired and has uses left. */
        public boolean isUsable(Instant now) {
            return status == Status.ACTIVE && remainingUses > 0 && !isExpired(now);
        }

        public boolean isUsable() {
            return isUsable(Instant.now());
        }
    }

    /**
     * The next 15th of a month, 00:00 UTC, strictly after {@code from}'s date: from the 1st-14th
     * that is this month's 15th, from the 15th onwards it is next month's. So a key created
     * 2026-11-18 expires 2026-12-15 and one created 2026-11-10 expires 2026-11-15. The same rule is
     * implemented in scripts/new-license-key.ps1.
     */
    public static Instant nextExpiry(Instant from) {
        LocalDate day = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate fifteenth = day.withDayOfMonth(15);
        if (day.getDayOfMonth() >= 15) {
            fifteenth = fifteenth.plusMonths(1);
        }
        return fifteenth.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public LicenseStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    /**
     * The first four characters of a key, lower-cased as the key was issued ({@code def_},
     * {@code clh5}, ...). Safe to log: it is the issuing prefix, not the secret part, and it is
     * what makes a log line attributable to "whose key" without exposing the key itself.
     */
    public static String keyPrefix(String key) {
        String normalized = normalize(key);
        if (normalized == null || normalized.isEmpty()) return null;
        return normalized.substring(0, Math.min(4, normalized.length())).toLowerCase(Locale.ROOT);
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
                status,
                item.get("model") == null ? null : item.get("model").s(),
                item.get("expiresAt") == null || item.get("expiresAt").s() == null
                        ? null : Instant.parse(item.get("expiresAt").s()));
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

    /** Creates a new active license on the default model. */
    public void create(String key, int totalUses, String ownerLabel) {
        create(key, totalUses, ownerLabel, null);
    }

    /**
     * Creates a new active license. Fails if the key already exists, so a key is never
     * overwritten. {@code model} is an OpenRouter model id that overrides the configured default
     * for analyses run with this key (for trying weaker/cheaper models), or {@code null}.
     * The model lives on the record, never derived from the key's text: a prefix is only a label.
     */
    public void create(String key, int totalUses, String ownerLabel, String model) {
        create(key, totalUses, ownerLabel, model, nextExpiry(Instant.now()));
    }

    /** As above, with an explicit expiry ({@code null} = never expires). */
    public void create(String key, int totalUses, String ownerLabel, String model, Instant expiresAt) {
        requireTable();
        Map<String, AttributeValue> item = new HashMap<>(partitionKey(key));
        item.put("totalUses", number(totalUses));
        item.put("remainingUses", number(totalUses));
        item.put("status", AttributeValue.builder().s("active").build());
        item.put("ownerLabel", AttributeValue.builder().s(ownerLabel == null ? "" : ownerLabel).build());
        item.put("createdAt", AttributeValue.builder().s(Instant.now().toString()).build());
        if (expiresAt != null) {
            item.put("expiresAt", AttributeValue.builder().s(expiresAt.toString()).build());
        }
        if (model != null && !model.isBlank()) {
            item.put("model", AttributeValue.builder().s(model.trim()).build());
        }
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
