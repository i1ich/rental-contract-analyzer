package com.leaselens;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaselens.model.AnalysisResult;
import com.leaselens.model.AnalyzeContractRequest;
import com.leaselens.service.OpenRouterAnalysisService;
import com.leaselens.service.ContractAnalysisService;
import com.leaselens.service.ExtractTextInvoker;
import com.leaselens.service.ExtractTextResult;
import com.leaselens.service.LambdaExtractTextInvoker;
import com.leaselens.service.ServiceException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;

/**
 * Orchestration handler for contract analysis: fetch extracted text (via the extract-text
 * Lambda), analyze it with an LLM, cache the structured result in DynamoDB keyed by a hash of
 * the contract *text* (not the S3 object key, and not the raw document itself — per the
 * product's privacy design, identical contract text uploaded under any S3 key should hit the
 * same cache entry, and we never persist the raw PDF or its text in DynamoDB, only the
 * structured analysis result).
 */
public class AnalyzeContractHandler implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // Analyses are reusable (the same contract text always yields the same analysis) rather
    // than time-sensitive like a photo listing, so we cache them for a relatively long window.
    private static final int RESULTS_CACHE_TTL_DAYS = 30;

    // Below this length, extracted text is very unlikely to be an actual rental contract
    // (e.g. a mostly-blank page or an unrelated short document) — bail out early with a
    // friendly message rather than sending near-empty input to the LLM.
    private static final int MIN_CONTRACT_TEXT_LENGTH = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String tableName;
    private final ExtractTextInvoker extractTextInvoker;
    private final DynamoDbClient dynamoDb;
    private final ContractAnalysisService analysisService;

    /** No-arg constructor used by the Lambda runtime: wires up real AWS clients. */
    public AnalyzeContractHandler() {
        this(new LambdaExtractTextInvoker(), DynamoDbClient.create(), new OpenRouterAnalysisService());
    }

    /** Package-private constructor for tests: allows injecting fakes/mocks for all AWS-touching seams. */
    AnalyzeContractHandler(ExtractTextInvoker extractTextInvoker,
                            DynamoDbClient dynamoDb,
                            ContractAnalysisService analysisService) {
        this.tableName = System.getenv("TABLE_NAME");
        this.extractTextInvoker = extractTextInvoker;
        this.dynamoDb = dynamoDb;
        this.analysisService = analysisService;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            AnalyzeContractRequest request = parseRequest(event);
            if (request.getObjectKey() == null || request.getObjectKey().isBlank()) {
                return jsonResponse(400, Map.of("error", "objectKey is required"));
            }

            String objectKey = request.getObjectKey().trim();

            ExtractTextResult extracted = extractTextInvoker.extractText(objectKey);

            if (!extracted.isHasTextLayer()) {
                return jsonResponse(422, Map.of("error",
                        "No pudimos leer el texto de este PDF (parece un documento escaneado). "
                                + "El análisis de PDFs escaneados aún no está disponible."));
            }

            String contractText = extracted.getText() == null ? "" : extracted.getText().trim();
            if (contractText.length() < MIN_CONTRACT_TEXT_LENGTH) {
                return jsonResponse(422, Map.of("error",
                        "No pudimos analizar este documento. Verificá que sea un contrato de "
                                + "alquiler en formato PDF con texto."));
            }

            String contentHash = sha256Hex(contractText);

            AnalysisResult cached = loadFromCache(contentHash);
            if (cached != null) {
                return jsonResponse(200, cached);
            }

            AnalysisResult result = analysisService.analyze(contractText);
            result.setCachedAt(null);

            saveToCache(contentHash, result);

            return jsonResponse(200, result);
        } catch (ServiceException e) {
            return jsonResponse(502, Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return jsonResponse(400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            // Never leak raw contract text into logs or error bodies.
            System.err.println("Unexpected error [" + e.getClass().getName() + "]: " + e.getMessage());
            e.printStackTrace(System.err);
            return jsonResponse(500, Map.of("error", "Error interno del servidor"));
        }
    }

    private AnalyzeContractRequest parseRequest(APIGatewayProxyRequestEvent event) {
        String body = event.getBody();
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Request body is required");
        }
        try {
            return MAPPER.readValue(body, AnalyzeContractRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON body");
        }
    }

    private AnalysisResult loadFromCache(String contentHash) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalStateException("TABLE_NAME environment variable is not set");
        }

        var item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("contentHash", AttributeValue.builder().s(contentHash).build()))
                        .build())
                .item();

        if (item == null || item.isEmpty()) {
            return null;
        }

        AttributeValue ttlAttr = item.get("ttl");
        AttributeValue resultAttr = item.get("resultJson");
        if (ttlAttr == null || resultAttr == null || resultAttr.s() == null) {
            return null;
        }

        long ttlEpoch = Long.parseLong(ttlAttr.n());
        if (ttlEpoch <= Instant.now().getEpochSecond()) {
            return null;
        }

        try {
            AnalysisResult result = MAPPER.readValue(resultAttr.s(), AnalysisResult.class);
            result.setCachedAt(Instant.now().toString());
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize cached result", e);
        }
    }

    private void saveToCache(String contentHash, AnalysisResult result) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalStateException("TABLE_NAME environment variable is not set");
        }

        String resultJson;
        try {
            resultJson = MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize analysis result", e);
        }

        long ttlEpoch = Instant.now().plus(RESULTS_CACHE_TTL_DAYS, ChronoUnit.DAYS).getEpochSecond();

        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "contentHash", AttributeValue.builder().s(contentHash).build(),
                        "ttl", AttributeValue.builder().n(String.valueOf(ttlEpoch)).build(),
                        "resultJson", AttributeValue.builder().s(resultJson).build()))
                .build());
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
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
