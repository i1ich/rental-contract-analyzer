package com.leaselens;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link GenerateUploadUrlHandler} fully offline: {@link S3Presigner} signs locally,
 * so fake static credentials (never a real AWS account) are enough.
 */
class GenerateUploadUrlHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static S3Presigner fakePresigner() {
        return S3Presigner.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .build();
    }

    @Test
    void returns200WithUploadUrlAndObjectKey() throws Exception {
        GenerateUploadUrlHandler handler = new GenerateUploadUrlHandler("test-bucket", fakePresigner());

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(MAPPER.writeValueAsString(Map.of("fileSizeBytes", 1_000_000)));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);

        assertEquals(200, response.getStatusCode());
        JsonNode body = MAPPER.readTree(response.getBody());
        assertTrue(body.get("uploadUrl").asText().startsWith("https://"));
        assertTrue(body.get("objectKey").asText().startsWith("uploads/"));
        assertEquals("application/pdf", body.get("requiredContentType").asText());
        assertEquals(900, body.get("expiresInSeconds").asLong());
    }

    @Test
    void returns200WhenBodyIsMissing() {
        GenerateUploadUrlHandler handler = new GenerateUploadUrlHandler("test-bucket", fakePresigner());

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);

        assertEquals(200, response.getStatusCode());
    }

    @Test
    void returns400WhenDeclaredFileSizeExceedsCap() throws Exception {
        GenerateUploadUrlHandler handler = new GenerateUploadUrlHandler("test-bucket", fakePresigner());

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(MAPPER.writeValueAsString(
                Map.of("fileSizeBytes", GenerateUploadUrlHandler.MAX_UPLOAD_SIZE_BYTES + 1)));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void returns500WhenBucketNameNotConfigured() {
        GenerateUploadUrlHandler handler = new GenerateUploadUrlHandler(null, fakePresigner());

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);

        assertEquals(500, response.getStatusCode());
    }
}
