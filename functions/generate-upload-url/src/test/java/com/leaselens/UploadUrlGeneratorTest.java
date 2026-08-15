package com.leaselens;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for {@link UploadUrlGenerator}. {@link S3Presigner} signs entirely locally
 * (no network call), so fake static credentials are enough to exercise it without AWS access.
 */
class UploadUrlGeneratorTest {

    private static S3Presigner fakePresigner() {
        return S3Presigner.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .build();
    }

    @Test
    void generatesPresignedUrlForNewRandomPdfKeyUnderTheUploadsPrefix() {
        try (S3Presigner presigner = fakePresigner()) {
            UploadUrlGenerator.Result result =
                    UploadUrlGenerator.generate(presigner, "leaselens-contract-uploads", Duration.ofMinutes(15));

            assertTrue(result.objectKey().startsWith("uploads/"), "objectKey should live under uploads/");
            assertTrue(result.objectKey().endsWith(".pdf"), "objectKey should be a .pdf key");
            assertEquals("application/pdf", result.requiredContentType());
            assertEquals(900, result.expiresInSeconds());

            assertTrue(result.uploadUrl().contains("leaselens-contract-uploads"),
                    "presigned URL should target the given bucket");
            assertTrue(result.uploadUrl().contains(result.objectKey()),
                    "presigned URL should target the generated object key");
            // The signed-headers query param must include content-type, otherwise a PUT with a
            // different Content-Type header would still pass signature verification.
            assertTrue(result.uploadUrl().toLowerCase().contains("content-type"),
                    "presigned URL should bind the Content-Type header via the signature");
        }
    }

    @Test
    void generatesADifferentObjectKeyOnEachCall() {
        try (S3Presigner presigner = fakePresigner()) {
            UploadUrlGenerator.Result first =
                    UploadUrlGenerator.generate(presigner, "bucket", Duration.ofMinutes(15));
            UploadUrlGenerator.Result second =
                    UploadUrlGenerator.generate(presigner, "bucket", Duration.ofMinutes(15));

            assertTrue(!first.objectKey().equals(second.objectKey()), "object keys should be unique per call");
        }
    }
}
