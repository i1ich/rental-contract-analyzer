package com.leaselens;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.textract.TextractClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Extracts text from an uploaded PDF stored in S3, so downstream LLM analysis has clean
 * text to work with. Invoked directly (Lambda-to-Lambda) by an orchestration Lambda, not
 * behind API Gateway, so this handler works with plain {@code Map<String, Object>}
 * payloads rather than the API Gateway proxy envelope.
 *
 * <p>When the PDF has no native text layer (scanned/photographed contract), falls back to
 * OCR via {@link TextractOcrExtractor} (T6) so downstream code always gets a best-effort
 * {@code text} value rather than an empty one.
 */
public class ExtractTextHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final String bucketName = System.getenv("BUCKET_NAME");
    private final S3Client s3Client = S3Client.create();
    private final TextractClient textractClient = TextractClient.create();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        if (bucketName == null || bucketName.isBlank()) {
            throw new RuntimeException("BUCKET_NAME environment variable is not set");
        }

        Object objectKeyValue = input != null ? input.get("objectKey") : null;
        if (!(objectKeyValue instanceof String objectKey) || objectKey.isBlank()) {
            throw new RuntimeException("Missing or invalid 'objectKey' in request payload");
        }

        byte[] pdfBytes = readObject(objectKey);
        PdfTextExtractor.ExtractionResult result = PdfTextExtractor.extract(pdfBytes);

        if (result.hasTextLayer()) {
            return toResponse(result.text(), result.pageCount(), true, result.source());
        }

        // No native text layer — fall back to Textract OCR. The document is already in S3,
        // so Textract reads it directly by bucket/key; no bytes need to be re-uploaded.
        String ocrText;
        try {
            ocrText = TextractOcrExtractor.extractText(textractClient, bucketName, objectKey);
        } catch (Exception e) {
            // OCR is best-effort: log only the failure class (never contract content) and fall
            // through to an empty result. analyze-contract already treats too-little-text as a
            // clean "couldn't analyze" 422 rather than surfacing a 500/502 for this.
            System.err.println("Textract OCR failed for object [class=" + e.getClass().getName()
                    + "]: " + e.getMessage());
            return toResponse("", result.pageCount(), false, "none");
        }

        boolean ocrFoundText = ocrText != null && !ocrText.isBlank();
        return toResponse(ocrText, result.pageCount(), false, ocrFoundText ? "ocr" : "none");
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
     * Fetches the object bytes from S3, wrapping expected failure modes (missing object,
     * S3 service errors) into a descriptive {@link RuntimeException}. This Lambda isn't
     * behind API Gateway, so there's no HTTP status to set; letting the exception
     * propagate causes Lambda to report the invocation failure to the caller.
     */
    private byte[] readObject(String objectKey) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(request);
            return objectBytes.asByteArray();
        } catch (NoSuchKeyException e) {
            throw new RuntimeException("Object not found in bucket " + bucketName + ": " + objectKey, e);
        } catch (S3Exception e) {
            throw new RuntimeException("Failed to read object from S3: " + objectKey, e);
        }
    }
}
