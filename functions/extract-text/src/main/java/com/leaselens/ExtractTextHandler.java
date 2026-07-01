package com.leaselens;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.core.ResponseBytes;
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
 */
public class ExtractTextHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final String bucketName = System.getenv("BUCKET_NAME");
    private final S3Client s3Client = S3Client.create();

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

        Map<String, Object> response = new HashMap<>();
        response.put("text", result.text());
        response.put("pageCount", result.pageCount());
        response.put("hasTextLayer", result.hasTextLayer());
        response.put("source", result.source());
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
