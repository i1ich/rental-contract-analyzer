package com.leaselens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real implementation of {@link AnalysisWorkerInvoker}: fires the analyze-worker Lambda with
 * {@link InvocationType#EVENT}, so the call returns as soon as Lambda has accepted the payload
 * rather than when the analysis finishes.
 *
 * <p>{@code EVENT} is what makes the async endpoint async — with {@code REQUEST_RESPONSE} (what
 * {@link LambdaExtractTextInvoker} correctly uses, since it genuinely needs the text back) the API
 * handler would block for the full ~90s analysis and reintroduce the 29s timeout this whole design
 * exists to escape.
 */
public class LambdaAnalysisWorkerInvoker implements AnalysisWorkerInvoker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String functionName = System.getenv("ANALYZE_WORKER_FUNCTION_NAME");
    private final LambdaClient lambdaClient = LambdaClient.create();

    @Override
    public void startAnalysis(String jobId, String objectKey, String licenseKey) {
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalStateException("ANALYZE_WORKER_FUNCTION_NAME environment variable is not set");
        }

        String payload;
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("jobId", jobId);
            fields.put("objectKey", objectKey);
            if (licenseKey != null) {
                fields.put("licenseKey", licenseKey);
            }
            payload = MAPPER.writeValueAsString(fields);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build analyze-worker request payload", e);
        }

        try {
            lambdaClient.invoke(InvokeRequest.builder()
                    .functionName(functionName)
                    .invocationType(InvocationType.EVENT)
                    .payload(SdkBytes.fromString(payload, StandardCharsets.UTF_8))
                    .build());
        } catch (Exception e) {
            throw new ServiceException("Failed to start the analysis worker: " + e.getMessage(), e);
        }
    }
}
