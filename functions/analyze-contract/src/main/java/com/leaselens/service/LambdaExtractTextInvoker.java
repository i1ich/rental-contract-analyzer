package com.leaselens.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import java.nio.charset.StandardCharsets;

/**
 * Real implementation of {@link ExtractTextInvoker}: invokes the {@code extract-text} Lambda
 * directly (Lambda-to-Lambda), not through API Gateway.
 */
public class LambdaExtractTextInvoker implements ExtractTextInvoker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String functionName = System.getenv("EXTRACT_TEXT_FUNCTION_NAME");
    private final LambdaClient lambdaClient = LambdaClient.create();

    @Override
    public ExtractTextResult extractText(String objectKey) {
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalStateException("EXTRACT_TEXT_FUNCTION_NAME environment variable is not set");
        }

        String payload;
        try {
            payload = MAPPER.writeValueAsString(java.util.Map.of("objectKey", objectKey));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build extract-text request payload", e);
        }

        InvokeResponse response;
        try {
            response = lambdaClient.invoke(InvokeRequest.builder()
                    .functionName(functionName)
                    .invocationType(InvocationType.REQUEST_RESPONSE)
                    .payload(SdkBytes.fromString(payload, StandardCharsets.UTF_8))
                    .build());
        } catch (Exception e) {
            throw new ServiceException("Failed to invoke extract-text Lambda: " + e.getMessage(), e);
        }

        if (response.functionError() != null && !response.functionError().isBlank()) {
            throw new ServiceException("extract-text Lambda returned an error: " + response.functionError());
        }

        String responseBody = response.payload().asString(StandardCharsets.UTF_8);
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            ExtractTextResult result = new ExtractTextResult();
            result.setText(root.path("text").asText(""));
            result.setPageCount(root.path("pageCount").asInt(0));
            result.setHasTextLayer(root.path("hasTextLayer").asBoolean(false));
            result.setSource(root.path("source").asText(null));
            return result;
        } catch (Exception e) {
            throw new ServiceException("Failed to parse extract-text Lambda response: " + e.getMessage(), e);
        }
    }
}
