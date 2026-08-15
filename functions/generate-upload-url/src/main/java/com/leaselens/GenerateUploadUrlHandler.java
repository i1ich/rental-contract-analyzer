package com.leaselens;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.util.Map;

/**
 * Lets the browser upload a PDF directly to S3 without proxying bytes through the backend: on
 * request, returns a presigned S3 PUT URL plus the object key the browser must upload to.
 *
 * <p>Behind API Gateway (unlike extract-text/analyze-contract's Lambda-to-Lambda calls), so
 * this works with the API Gateway proxy envelope, mirroring {@code AnalyzeContractHandler}.
 */
public class GenerateUploadUrlHandler implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    /**
     * Client-declared max upload size. This is a best-effort, application-level check on the
     * {@code fileSizeBytes} the caller reports before we hand out a URL — a presigned PUT
     * (unlike a presigned POST policy) has no built-in way to bound the actual byte count of
     * what gets uploaded through it. Hard enforcement (reject/expire oversized objects
     * server-side) is T13's job; this is the "soft" cap anticipated by T4's deliverable.
     */
    static final long MAX_UPLOAD_SIZE_BYTES = 10L * 1024 * 1024;

    private static final Duration URL_VALIDITY = Duration.ofMinutes(15);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String bucketName;
    private final S3Presigner presigner;

    /** No-arg constructor used by the Lambda runtime: wires up the real S3 presigner. */
    public GenerateUploadUrlHandler() {
        this(System.getenv("BUCKET_NAME"), S3Presigner.create());
    }

    /** Package-private constructor for tests: allows injecting a presigner with fake credentials. */
    GenerateUploadUrlHandler(String bucketName, S3Presigner presigner) {
        this.bucketName = bucketName;
        this.presigner = presigner;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        if (bucketName == null || bucketName.isBlank()) {
            System.err.println("BUCKET_NAME environment variable is not set");
            return jsonResponse(500, Map.of("error", "Error interno del servidor"));
        }

        Long declaredSizeBytes = parseDeclaredSizeBytes(event);
        if (declaredSizeBytes != null && declaredSizeBytes > MAX_UPLOAD_SIZE_BYTES) {
            return jsonResponse(400, Map.of("error",
                    "El archivo supera el tamaño máximo permitido (10 MB)."));
        }

        UploadUrlGenerator.Result result = UploadUrlGenerator.generate(presigner, bucketName, URL_VALIDITY);

        return jsonResponse(200, Map.of(
                "uploadUrl", result.uploadUrl(),
                "objectKey", result.objectKey(),
                "requiredContentType", result.requiredContentType(),
                "expiresInSeconds", result.expiresInSeconds()));
    }

    /** Request body is optional: {@code {"fileSizeBytes": <number>}}. Missing/invalid → no size check. */
    private Long parseDeclaredSizeBytes(APIGatewayProxyRequestEvent event) {
        String body = event.getBody();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode sizeNode = root.get("fileSizeBytes");
            return (sizeNode != null && sizeNode.isNumber()) ? sizeNode.asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private APIGatewayProxyResponseEvent jsonResponse(int statusCode, Object body) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withHeaders(Map.of(
                            "Content-Type", "application/json",
                            "Access-Control-Allow-Origin", "*"))
                    .withBody(MAPPER.writeValueAsString(body));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize response", e);
        }
    }
}
