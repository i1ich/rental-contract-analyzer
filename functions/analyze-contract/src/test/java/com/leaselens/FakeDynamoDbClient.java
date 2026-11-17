package com.leaselens;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal in-memory fake for {@link DynamoDbClient}: only implements getItem/putItem, which are
 * the only operations {@code AnalyzeContractHandler} calls. All other interface methods keep
 * their default (UnsupportedOperationException) behavior from the SDK, which is fine since the
 * handler never calls them.
 */
public class FakeDynamoDbClient implements DynamoDbClient {

    private final Map<String, Map<String, AttributeValue>> store = new HashMap<>();

    @Override
    public GetItemResponse getItem(GetItemRequest request) {
        String key = request.key().get("contentHash").s();
        Map<String, AttributeValue> item = store.get(key);
        if (item == null) {
            return GetItemResponse.builder().build();
        }
        return GetItemResponse.builder().item(item).build();
    }

    @Override
    public PutItemResponse putItem(PutItemRequest request) {
        String key = request.item().get("contentHash").s();
        store.put(key, request.item());
        return PutItemResponse.builder().build();
    }

    /**
     * Supports only the license-spend update ({@code LicenseStore.consumeOne}): decrements
     * {@code remainingUses} when it is positive and the license is active, otherwise throws the
     * same exception DynamoDB raises for a failed condition.
     */
    @Override
    public UpdateItemResponse updateItem(UpdateItemRequest request) {
        String key = request.key().get("contentHash").s();
        Map<String, AttributeValue> item = store.get(key);
        boolean active = item != null && item.get("status") != null
                && "active".equals(item.get("status").s());
        if (item == null || !active || Integer.parseInt(item.get("remainingUses").n()) <= 0) {
            throw ConditionalCheckFailedException.builder().message("condition failed").build();
        }
        Map<String, AttributeValue> updated = new HashMap<>(item);
        AttributeValue remaining = AttributeValue.builder()
                .n(String.valueOf(Integer.parseInt(item.get("remainingUses").n()) - 1)).build();
        updated.put("remainingUses", remaining);
        updated.put("lastUsedAt", request.expressionAttributeValues().get(":now"));
        store.put(key, updated);
        return UpdateItemResponse.builder().attributes(Map.of("remainingUses", remaining)).build();
    }

    @Override
    public String serviceName() {
        return "FakeDynamoDbClient";
    }

    @Override
    public void close() {
        // no-op
    }
}
