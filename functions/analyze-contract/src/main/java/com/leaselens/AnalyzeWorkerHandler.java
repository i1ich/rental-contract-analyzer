package com.leaselens;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaselens.model.AnalysisResult;
import com.leaselens.service.AnalysisJobStore;
import com.leaselens.service.ContractAnalysisService;
import com.leaselens.service.ExtractTextInvoker;
import com.leaselens.service.ExtractTextResult;
import com.leaselens.service.LambdaExtractTextInvoker;
import com.leaselens.service.OpenRouterAnalysisService;
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
 * The actual analysis pipeline, run in the background: fetch extracted text (via the extract-text
 * Lambda), analyze it with an LLM, cache the structured result in DynamoDB keyed by a hash of the
 * contract *text* (not the S3 object key, and not the raw document itself — per the product's
 * privacy design, identical contract text uploaded under any S3 key hits the same cache entry,
 * and we never persist the raw PDF or its text, only the structured analysis result).
 *
 * <p>This code used to run inside {@link AnalyzeContractHandler} on the synchronous request path.
 * It was moved here unchanged in substance because a real analysis takes ~83-92s and API Gateway
 * caps a REST integration at a hard 29s, so every cache-miss request 504'd on the client while
 * this work quietly succeeded server-side. Now {@code POST /analyze} only accepts the job and this
 * handler is invoked asynchronously ({@code InvocationType.EVENT}), reporting progress through
 * {@link AnalysisJobStore} instead of an HTTP response.
 *
 * <p>Invoked Lambda-to-Lambda with {@code {"jobId": "...", "objectKey": "..."}}; it is not exposed
 * through API Gateway and returns nothing meaningful to its caller (an EVENT invocation has no
 * listener). **Every** outcome, success or failure, must therefore be written to the job record —
 * an unrecorded crash would leave a client polling PENDING forever.
 */
public class AnalyzeWorkerHandler implements RequestHandler<Map<String, Object>, String> {

    // Analyses are reusable (the same contract text always yields the same analysis) rather
    // than time-sensitive like a photo listing, so we cache them for a relatively long window.
    private static final int RESULTS_CACHE_TTL_DAYS = 30;

    // Below this length, extracted text is very unlikely to be an actual rental contract
    // (e.g. a mostly-blank page or an unrelated short document) — bail out early with a
    // friendly message rather than sending near-empty input to the LLM.
    private static final int MIN_CONTRACT_TEXT_LENGTH = 200;

    // T13 cost/abuse guard: caps LLM input size regardless of source. A real Uruguayan rental
    // contract with this checklist is a few thousand words; 60,000 chars (~15K tokens) leaves
    // generous headroom for a long contract with attachments while bounding the worst case of a
    // pathological/malicious upload (e.g. a text-layer PDF stuffed with megabytes of embedded
    // text) from blowing up OpenRouter cost. extract-text's own MAX_OCR_PAGES guard covers the
    // scanned-document side of this same concern.
    private static final int MAX_CONTRACT_TEXT_LENGTH = 60_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String tableName;
    private final ExtractTextInvoker extractTextInvoker;
    private final DynamoDbClient dynamoDb;
    private final ContractAnalysisService analysisService;
    private final AnalysisJobStore jobStore;

    /** No-arg constructor used by the Lambda runtime: wires up real AWS clients. */
    public AnalyzeWorkerHandler() {
        this(new LambdaExtractTextInvoker(), DynamoDbClient.create(), new OpenRouterAnalysisService());
    }

    /** Package-private constructor for tests: allows injecting fakes for all AWS-touching seams. */
    AnalyzeWorkerHandler(ExtractTextInvoker extractTextInvoker,
                         DynamoDbClient dynamoDb,
                         ContractAnalysisService analysisService) {
        this.tableName = System.getenv("TABLE_NAME");
        this.extractTextInvoker = extractTextInvoker;
        this.dynamoDb = dynamoDb;
        this.analysisService = analysisService;
        this.jobStore = new AnalysisJobStore(dynamoDb, this.tableName);
    }

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        String jobId = stringField(event, "jobId");
        String objectKey = stringField(event, "objectKey");

        if (jobId == null || jobId.isBlank()) {
            // Nothing to report progress against — fail loudly in the logs instead of silently.
            throw new IllegalArgumentException("jobId is required");
        }

        try {
            if (objectKey == null || objectKey.isBlank()) {
                jobStore.markFailed(jobId, 400, "objectKey is required");
                return "failed";
            }

            AnalysisResult result = analyze(objectKey.trim());
            jobStore.markDone(jobId, MAPPER.writeValueAsString(result));
            return "done";
        } catch (UnusableTextException e) {
            jobStore.markFailed(jobId, 422, e.getMessage());
            return "failed";
        } catch (ServiceException e) {
            jobStore.markFailed(jobId, 502, e.getMessage());
            return "failed";
        } catch (Exception e) {
            // Never leak raw contract text into logs or stored error messages.
            System.err.println("Unexpected error [" + e.getClass().getName() + "]: " + e.getMessage());
            e.printStackTrace(System.err);
            try {
                jobStore.markFailed(jobId, 500, "Error interno del servidor");
            } catch (Exception storeFailure) {
                // If even recording the failure fails, let Lambda log the original problem —
                // but don't mask it with the secondary one.
                System.err.println("Also failed to record job failure [" + storeFailure.getClass().getName() + "]");
            }
            return "failed";
        }
    }

    private AnalysisResult analyze(String objectKey) {
        // extract-text already falls back to vision-model OCR (T6) when there's no native text
        // layer, so "text too short" is the single signal here for "couldn't get usable text" —
        // whether that's because it's not a contract, a blank page, or OCR came up empty on a
        // low-quality scan.
        ExtractTextResult extracted = extractTextInvoker.extractText(objectKey);

        String contractText = extracted.getText() == null ? "" : extracted.getText().trim();
        if (contractText.length() < MIN_CONTRACT_TEXT_LENGTH) {
            throw new UnusableTextException(
                    "No pudimos extraer suficiente texto de este documento (incluso probando "
                            + "reconocimiento óptico para escaneos). Verificá que sea un contrato "
                            + "de alquiler en formato PDF legible.");
        }
        if (contractText.length() > MAX_CONTRACT_TEXT_LENGTH) {
            // Never log the truncated-off content itself — just the fact and the length.
            System.err.println("Truncating oversized contract text [originalLength=" + contractText.length() + "]");
            contractText = contractText.substring(0, MAX_CONTRACT_TEXT_LENGTH);
        }

        String contentHash = sha256Hex(contractText);

        AnalysisResult cached = loadFromCache(contentHash);
        if (cached != null) {
            return cached;
        }

        AnalysisResult result = analysisService.analyze(contractText);
        result.setCachedAt(null);
        saveToCache(contentHash, result);
        return result;
    }

    /** Signals "we couldn't get usable text out of this document" — maps to a 422 for the client. */
    private static class UnusableTextException extends RuntimeException {
        UnusableTextException(String message) {
            super(message);
        }
    }

    private static String stringField(Map<String, Object> event, String name) {
        if (event == null) return null;
        Object value = event.get(name);
        return value instanceof String s ? s : null;
    }

    private AnalysisResult loadFromCache(String contentHash) {
        requireTable();

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
        requireTable();

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

    private void requireTable() {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalStateException("TABLE_NAME environment variable is not set");
        }
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
}
