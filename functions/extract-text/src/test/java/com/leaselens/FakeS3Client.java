package com.leaselens;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal in-memory fake for {@link S3Client}: only implements getObjectAsBytes, which is the
 * only operation {@code ExtractTextHandler} calls. All other interface methods keep their
 * default (UnsupportedOperationException) behavior from the SDK.
 */
public class FakeS3Client implements S3Client {

    private record Stored(byte[] bytes, String eTag) {
    }

    private final Map<String, Stored> objects = new HashMap<>();

    /** Stores an object with an ETag derived from its bytes (mirrors real S3's content-based ETag). */
    public void putObject(String key, byte[] bytes) {
        putObject(key, bytes, "\"" + Integer.toHexString(Arrays.hashCode(bytes)) + "\"");
    }

    /** Stores an object with an explicit ETag, for tests that need precise control over it. */
    public void putObject(String key, byte[] bytes, String eTag) {
        objects.put(key, new Stored(bytes, eTag));
    }

    @Override
    public ResponseBytes<GetObjectResponse> getObjectAsBytes(GetObjectRequest getObjectRequest) {
        Stored stored = objects.get(getObjectRequest.key());
        if (stored == null) {
            throw NoSuchKeyException.builder().message("No such key: " + getObjectRequest.key()).build();
        }
        return ResponseBytes.fromByteArray(
                GetObjectResponse.builder().eTag(stored.eTag()).build(), stored.bytes());
    }

    @Override
    public String serviceName() {
        return "FakeS3Client";
    }

    @Override
    public void close() {
        // no-op
    }
}
