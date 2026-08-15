package com.leaselens;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal in-memory fake for {@link DynamoDbClient}: only implements getItem/putItem, which are
 * the only operations {@link OcrTextCache} calls. All other interface methods keep their
 * default (UnsupportedOperationException) behavior from the SDK.
 */
public class FakeDynamoDbClient implements DynamoDbClient {

    private final Map<String, Map<String, AttributeValue>> store = new HashMap<>();
    private int getItemCallCount = 0;
    private int putItemCallCount = 0;

    @Override
    public GetItemResponse getItem(GetItemRequest request) {
        getItemCallCount++;
        String key = request.key().get("contentHash").s();
        Map<String, AttributeValue> item = store.get(key);
        if (item == null) {
            return GetItemResponse.builder().build();
        }
        return GetItemResponse.builder().item(item).build();
    }

    @Override
    public PutItemResponse putItem(PutItemRequest request) {
        putItemCallCount++;
        String key = request.item().get("contentHash").s();
        store.put(key, request.item());
        return PutItemResponse.builder().build();
    }

    public int getItemCallCount() {
        return getItemCallCount;
    }

    public int putItemCallCount() {
        return putItemCallCount;
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
