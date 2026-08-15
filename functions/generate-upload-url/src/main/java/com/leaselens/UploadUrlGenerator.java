package com.leaselens;

import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

/**
 * Core "presign a PUT URL" logic, kept separate from the Lambda handler so it can be unit
 * tested offline (no real AWS credentials or network calls — {@link S3Presigner} signs
 * locally) using a presigner built from fake static credentials.
 */
public final class UploadUrlGenerator {

    static final String OBJECT_KEY_PREFIX = "uploads/";

    /**
     * The content-type required of the eventual PUT. Setting it on the {@link PutObjectRequest}
     * that gets presigned makes the SDK include {@code content-type} in the signed headers, so
     * S3 rejects (403) a PUT whose {@code Content-Type} header doesn't match exactly — this is
     * how "enforces application/pdf content-type" (T4's deliverable) is actually achieved with a
     * presigned PUT, as opposed to a presigned POST policy.
     */
    static final String REQUIRED_CONTENT_TYPE = "application/pdf";

    private UploadUrlGenerator() {
    }

    /**
     * Generates a fresh random object key under {@link #OBJECT_KEY_PREFIX} and a presigned PUT
     * URL for it, valid for {@code urlValidity}.
     */
    public static Result generate(S3Presigner presigner, String bucketName, Duration urlValidity) {
        String objectKey = OBJECT_KEY_PREFIX + UUID.randomUUID() + ".pdf";

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType(REQUIRED_CONTENT_TYPE)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(urlValidity)
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);

        return new Result(presigned.url().toString(), objectKey, REQUIRED_CONTENT_TYPE, urlValidity.getSeconds());
    }

    public record Result(String uploadUrl, String objectKey, String requiredContentType, long expiresInSeconds) {
    }
}
