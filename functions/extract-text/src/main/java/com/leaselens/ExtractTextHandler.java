package com.leaselens;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.HashMap;
import java.util.Map;

/**
 * Extracts text from an uploaded PDF stored in S3, so downstream LLM analysis has clean
 * text to work with. Invoked directly (Lambda-to-Lambda) by an orchestration Lambda, not
 * behind API Gateway, so this handler works with plain {@code Map<String, Object>}
 * payloads rather than the API Gateway proxy envelope.
 *
 * <p>When the PDF has no native text layer (scanned/photographed contract), falls back to
 * vision-model OCR via {@link VisionOcrExtractor} (T6) so downstream code always gets a
 * best-effort {@code text} value rather than an empty one. This is not AWS Textract: Textract
 * has no regional endpoint in {@code sa-east-1}, where this stack is deployed, so OCR goes
 * through a vision-capable LLM reached via OpenRouter instead — see {@link OpenRouterVisionClient}.
 *
 * <p>The OCR step's output isn't fully deterministic between calls on the same image, so
 * successful transcriptions are cached by the object's own S3 ETag ({@link OcrTextCache}) —
 * a retry of the exact same upload reuses the same text instead of risking a different (and
 * possibly lower-quality) transcription each time.
 */
public class ExtractTextHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final String bucketName = System.getenv("BUCKET_NAME");
    private final S3Client s3Client;
    private final VisionTranscriptionClient visionClient;
    private final OcrTextCache ocrTextCache;

    /** No-arg constructor used by the Lambda runtime: wires up real AWS/OpenRouter clients. */
    public ExtractTextHandler() {
        this(S3Client.create(), new OpenRouterVisionClient(),
                new OcrTextCache(DynamoDbClient.create(), System.getenv("TABLE_NAME")));
    }

    /** Package-private constructor for tests: allows injecting fakes for every AWS/network-touching seam. */
    ExtractTextHandler(S3Client s3Client, VisionTranscriptionClient visionClient, OcrTextCache ocrTextCache) {
        this.s3Client = s3Client;
        this.visionClient = visionClient;
        this.ocrTextCache = ocrTextCache;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        if (bucketName == null || bucketName.isBlank()) {
            throw new RuntimeException("BUCKET_NAME environment variable is not set");
        }

        Object objectKeyValue = input != null ? input.get("objectKey") : null;
        if (!(objectKeyValue instanceof String objectKey) || objectKey.isBlank()) {
            throw new RuntimeException("Missing or invalid 'objectKey' in request payload");
        }

        ResponseBytes<GetObjectResponse> object = readObject(objectKey);
        byte[] pdfBytes = object.asByteArray();
        PdfTextExtractor.ExtractionResult result = PdfTextExtractor.extract(pdfBytes);

        if (result.hasTextLayer()) {
            return toResponse(result.text(), result.pageCount(), true, result.source());
        }

        // No native text layer — check whether we've already OCR'd this exact object before
        // (by its S3 ETag) so a retry doesn't risk a different, possibly worse, transcription.
        String eTag = object.response().eTag();
        String cachedText = ocrTextCache.get(eTag);
        if (cachedText != null) {
            return toResponse(cachedText, result.pageCount(), false, "vision-ocr-cached");
        }

        // Not cached — fall back to vision-model OCR. The PDF bytes are already in hand (read
        // from S3 above), so pages are rendered locally with PDFBox and sent to the vision
        // model directly; nothing needs to be re-uploaded or staged anywhere.
        String ocrText;
        try {
            ocrText = VisionOcrExtractor.extractText(pdfBytes, visionClient);
        } catch (Exception e) {
            // OCR is best-effort: log only the failure class (never contract content) and fall
            // through to an empty result. analyze-contract already treats too-little-text as a
            // clean "couldn't analyze" 422 rather than surfacing a 500/502 for this. Failures
            // aren't cached, so a later retry gets a fresh attempt rather than being stuck with
            // a permanent empty result.
            System.err.println("Vision OCR failed for object [class=" + e.getClass().getName()
                    + "]: " + e.getMessage());
            return toResponse("", result.pageCount(), false, "none");
        }

        boolean ocrFoundText = ocrText != null && !ocrText.isBlank();
        if (ocrFoundText) {
            ocrTextCache.put(eTag, ocrText);
        }
        return toResponse(ocrText, result.pageCount(), false, ocrFoundText ? "vision-ocr" : "none");
    }

    private Map<String, Object> toResponse(String text, int pageCount, boolean hasTextLayer, String source) {
        Map<String, Object> response = new HashMap<>();
        response.put("text", text);
        response.put("pageCount", pageCount);
        response.put("hasTextLayer", hasTextLayer);
        response.put("source", source);
        return response;
    }

    /**
     * Fetches the object (bytes + metadata, including its ETag) from S3, wrapping expected
     * failure modes (missing object, S3 service errors) into a descriptive
     * {@link RuntimeException}. This Lambda isn't behind API Gateway, so there's no HTTP status
     * to set; letting the exception propagate causes Lambda to report the invocation failure to
     * the caller.
     */
    private ResponseBytes<GetObjectResponse> readObject(String objectKey) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            return s3Client.getObjectAsBytes(request);
        } catch (NoSuchKeyException e) {
            throw new RuntimeException("Object not found in bucket " + bucketName + ": " + objectKey, e);
        } catch (S3Exception e) {
            throw new RuntimeException("Failed to read object from S3: " + objectKey, e);
        }
    }
}
