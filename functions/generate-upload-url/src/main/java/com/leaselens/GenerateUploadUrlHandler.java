package com.leaselens;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaselens.service.DisclaimerGate;
import com.leaselens.service.LicenseGate;
import com.leaselens.service.LicenseStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Lets the browser upload a PDF directly to S3 without proxying bytes through the backend: on
 * request, returns a presigned S3 PUT URL plus the object key the browser must upload to.
 *
 * <p>Behind API Gateway (unlike extract-text/analyze-contract's Lambda-to-Lambda calls), so
 * this works with the API Gateway proxy envelope, mirroring {@code AnalyzeContractHandler}.
 *
 * <p>Gated exactly like {@code POST /analyze}: without it a bare {@code curl} would get a working
 * upload URL and land a file in S3 with no key and no disclaimer on record. Order is cheapest
 * first and always before S3: disclaimer (pure check), then license (one read-only DynamoDB get,
 * never charged here — a use is spent only by the analysis worker), then the presigner.
 */
public class GenerateUploadUrlHandler implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    /**
     * Client-declared max upload size. This is a best-effort, application-level check on the
     * {@code fileSizeBytes} the caller reports before we hand out a URL — a presigned PUT
     * (unlike a presigned POST policy) has no built-in way to bound the actual byte count of
     * what gets uploaded through it: {@code s3:content-length-range} is a presigned-POST-only
     * policy condition, not enforceable via bucket/IAM policy on a SigV4-presigned PUT (verified
     * against AWS docs while building T13's guards). Real hard enforcement therefore can't live
     * here or in a bucket policy — it's done downstream instead, where it also matters more:
     * {@code ExtractTextHandler.MAX_OBJECT_SIZE_BYTES} rejects an oversized object before ever
     * parsing it or spending OCR/LLM cost on it, which is the actual risk this guard exists to
     * bound. This client-declared check just avoids handing out a URL at all for an
     * obviously-oversized file when the client is honest about its size.
     */
    static final long MAX_UPLOAD_SIZE_BYTES = 10L * 1024 * 1024;

    private static final Duration URL_VALIDITY = Duration.ofMinutes(15);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String bucketName;
    private final S3Presigner presigner;
    private final LicenseStore licenseStore;
    private final boolean licenseRequired;

    /**
     * No-arg constructor used by the Lambda runtime: wires up the real S3 presigner and DynamoDB.
     * License enforcement follows the same {@code LICENSE_REQUIRED} variable as analyze-contract.
     */
    public GenerateUploadUrlHandler() {
        this(System.getenv("BUCKET_NAME"), S3Presigner.create(), DynamoDbClient.create(),
                "true".equalsIgnoreCase(System.getenv("LICENSE_REQUIRED")));
    }

    /** Package-private constructor for tests: injects a fake-credential presigner and DynamoDB. */
    GenerateUploadUrlHandler(String bucketName, S3Presigner presigner, DynamoDbClient dynamoDb,
                             boolean licenseRequired) {
        this.bucketName = bucketName;
        this.presigner = presigner;
        this.licenseStore = new LicenseStore(dynamoDb, System.getenv("TABLE_NAME"));
        this.licenseRequired = licenseRequired;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        Log.clearCorrelationId();

        if (bucketName == null || bucketName.isBlank()) {
            Log.warn("config_missing", "variable", "BUCKET_NAME");
            return jsonResponse(500, Map.of("error", "Error interno del servidor"));
        }

        JsonNode request = parseBody(event);

        // Disclaimer gate first: a pure check, and the same one /analyze applies.
        Boolean accepted = request.path("disclaimerAccepted").isBoolean()
                ? request.get("disclaimerAccepted").asBoolean() : null;
        String version = request.path("disclaimerVersion").isTextual()
                ? request.get("disclaimerVersion").asText() : null;
        if (!DisclaimerGate.isAccepted(accepted, version)) {
            Log.warn("disclaimer_rejected", "status", 400, "sentVersion", version, "accepted", accepted);
            return jsonResponse(400, DisclaimerGate.rejectionBody());
        }

        // License gate: read-only, nothing is charged here.
        if (licenseRequired) {
            String licenseKey = LicenseStore.normalize(
                    request.path("licenseKey").isTextual() ? request.get("licenseKey").asText() : null);
            LicenseGate.Rejection rejection = LicenseGate.check(licenseStore, licenseKey, Instant.now());
            if (rejection != null) {
                Log.warn("license_rejected", "status", rejection.status(), "reason", rejection.reason(),
                        "keyPrefix", LicenseStore.keyPrefix(licenseKey));
                return jsonResponse(rejection.status(), rejection.body());
            }
        }

        Long declaredSizeBytes = declaredSizeBytes(request);
        if (declaredSizeBytes != null && declaredSizeBytes > MAX_UPLOAD_SIZE_BYTES) {
            Log.warn("upload_rejected", "reason", "too-large",
                    "declaredSizeBytes", declaredSizeBytes, "maxBytes", MAX_UPLOAD_SIZE_BYTES);
            return jsonResponse(400, Map.of("error",
                    "El archivo supera el tamaño máximo permitido (10 MB)."));
        }

        UploadUrlGenerator.Result result = UploadUrlGenerator.generate(presigner, bucketName, URL_VALIDITY);

        // The object key is the join field for everything that happens to this upload later,
        // in both other Lambdas. Note the declared size is the client's claim, not enforcement.
        Log.event("upload_url_issued", "objectKey", result.objectKey(),
                "declaredSizeBytes", declaredSizeBytes,
                "expiresInSeconds", result.expiresInSeconds());

        return jsonResponse(200, Map.of(
                "uploadUrl", result.uploadUrl(),
                "objectKey", result.objectKey(),
                "requiredContentType", result.requiredContentType(),
                "expiresInSeconds", result.expiresInSeconds()));
    }

    /** The JSON body, or an empty node when it is missing or not valid JSON (which the gates then refuse). */
    private JsonNode parseBody(APIGatewayProxyRequestEvent event) {
        String body = event.getBody();
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            return root.isObject() ? root : MAPPER.createObjectNode();
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /** {@code fileSizeBytes} is optional. Missing/invalid → no size check. */
    private Long declaredSizeBytes(JsonNode request) {
        JsonNode sizeNode = request.get("fileSizeBytes");
        return (sizeNode != null && sizeNode.isNumber()) ? sizeNode.asLong() : null;
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
