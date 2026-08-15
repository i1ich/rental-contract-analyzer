package com.leaselens;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fully offline tests for {@link ExtractTextHandler}, covering both branches end-to-end: the
 * native text-layer path (no vision call at all) and the vision-OCR fallback path (success,
 * failure, and the ETag-keyed {@link OcrTextCache} that makes repeat requests for the same
 * object consistent), using {@link FakeS3Client}, {@link FakeVisionTranscriptionClient}, and
 * {@link FakeDynamoDbClient} for every AWS/network-touching seam. Requires {@code BUCKET_NAME}
 * and {@code TABLE_NAME} to be set in the test environment (configured via the Surefire plugin
 * in pom.xml).
 */
class ExtractTextHandlerTest {

    @Test
    void textLayerPdfNeverInvokesTheVisionClient() throws IOException {
        FakeS3Client s3 = new FakeS3Client();
        s3.putObject("uploads/contract.pdf", buildPdfWithText("Cláusula uno: el alquiler es de U$S 500."));
        FakeVisionTranscriptionClient vision = FakeVisionTranscriptionClient.returning("should never be called");
        ExtractTextHandler handler = new ExtractTextHandler(s3, vision, newCache());

        Map<String, Object> response = handler.handleRequest(Map.of("objectKey", "uploads/contract.pdf"), null);

        assertEquals(true, response.get("hasTextLayer"));
        assertEquals("text-layer", response.get("source"));
        assertTrue(((String) response.get("text")).contains("alquiler"));
        assertEquals(0, vision.callCount());
    }

    @Test
    void scannedPdfFallsBackToVisionOcrAndReturnsItsText() throws IOException {
        FakeS3Client s3 = new FakeS3Client();
        s3.putObject("uploads/scanned.pdf", buildBlankPdf(2));
        FakeVisionTranscriptionClient vision = FakeVisionTranscriptionClient.returning("texto reconocido por OCR");
        ExtractTextHandler handler = new ExtractTextHandler(s3, vision, newCache());

        Map<String, Object> response = handler.handleRequest(Map.of("objectKey", "uploads/scanned.pdf"), null);

        assertEquals(false, response.get("hasTextLayer"));
        assertEquals("vision-ocr", response.get("source"));
        assertEquals("texto reconocido por OCR", response.get("text"));
        assertEquals(1, vision.callCount());
    }

    @Test
    void visionOcrFailureFallsBackToEmptyResultInsteadOfPropagatingTheException() throws IOException {
        FakeS3Client s3 = new FakeS3Client();
        s3.putObject("uploads/scanned.pdf", buildBlankPdf(1));
        FakeVisionTranscriptionClient vision =
                FakeVisionTranscriptionClient.throwing(new RuntimeException("OpenRouter vision API error: HTTP 401"));
        ExtractTextHandler handler = new ExtractTextHandler(s3, vision, newCache());

        Map<String, Object> response = handler.handleRequest(Map.of("objectKey", "uploads/scanned.pdf"), null);

        assertEquals(false, response.get("hasTextLayer"));
        assertEquals("none", response.get("source"));
        assertEquals("", response.get("text"));
    }

    @Test
    void missingObjectKeyThrows() {
        ExtractTextHandler handler =
                new ExtractTextHandler(new FakeS3Client(), FakeVisionTranscriptionClient.returning(""), newCache());

        assertThrows(RuntimeException.class, () -> handler.handleRequest(Map.of(), null));
    }

    @Test
    void unknownObjectKeyThrowsRatherThanFallingBackToOcr() {
        FakeS3Client s3 = new FakeS3Client(); // no object registered
        ExtractTextHandler handler =
                new ExtractTextHandler(s3, FakeVisionTranscriptionClient.returning(""), newCache());

        assertThrows(RuntimeException.class,
                () -> handler.handleRequest(Map.of("objectKey", "uploads/missing.pdf"), null));
    }

    @Test
    void retryingTheExactSameUploadReusesTheCachedOcrTextInsteadOfCallingVisionAgain() throws IOException {
        FakeS3Client s3 = new FakeS3Client();
        // Same key AND same bytes both times — like a client retry (e.g. after a 504) of the
        // same presigned upload, not a second, different upload.
        byte[] scannedPdf = buildBlankPdf(1);
        s3.putObject("uploads/scanned.pdf", scannedPdf);
        FakeVisionTranscriptionClient vision = FakeVisionTranscriptionClient.returning("primera transcripcion");
        FakeDynamoDbClient dynamoDb = new FakeDynamoDbClient();
        ExtractTextHandler handler = new ExtractTextHandler(s3, vision, new OcrTextCache(dynamoDb, "leaselens-analyses-test"));

        Map<String, Object> first = handler.handleRequest(Map.of("objectKey", "uploads/scanned.pdf"), null);
        assertEquals("vision-ocr", first.get("source"));
        assertEquals("primera transcripcion", first.get("text"));
        assertEquals(1, vision.callCount());

        // Vision client would now return something different if called again — proves the
        // second response came from the cache, not a fresh (and differently-worded) OCR call.
        vision = FakeVisionTranscriptionClient.returning("una transcripcion completamente distinta");
        ExtractTextHandler retryHandler =
                new ExtractTextHandler(s3, vision, new OcrTextCache(dynamoDb, "leaselens-analyses-test"));

        Map<String, Object> second = retryHandler.handleRequest(Map.of("objectKey", "uploads/scanned.pdf"), null);

        assertEquals("vision-ocr-cached", second.get("source"));
        assertEquals("primera transcripcion", second.get("text"), "retry must return the same cached text, not a fresh transcription");
        assertEquals(0, vision.callCount(), "cache hit must never call the vision client");
    }

    @Test
    void failedOcrIsNotCachedSoARetryGetsAFreshAttempt() throws IOException {
        FakeS3Client s3 = new FakeS3Client();
        s3.putObject("uploads/scanned.pdf", buildBlankPdf(1));
        FakeDynamoDbClient dynamoDb = new FakeDynamoDbClient();
        FakeVisionTranscriptionClient failingVision =
                FakeVisionTranscriptionClient.throwing(new RuntimeException("HTTP 401"));
        ExtractTextHandler handler =
                new ExtractTextHandler(s3, failingVision, new OcrTextCache(dynamoDb, "leaselens-analyses-test"));
        handler.handleRequest(Map.of("objectKey", "uploads/scanned.pdf"), null);

        FakeVisionTranscriptionClient succeedingVision = FakeVisionTranscriptionClient.returning("ahora si funciono");
        ExtractTextHandler retryHandler =
                new ExtractTextHandler(s3, succeedingVision, new OcrTextCache(dynamoDb, "leaselens-analyses-test"));
        Map<String, Object> retry = retryHandler.handleRequest(Map.of("objectKey", "uploads/scanned.pdf"), null);

        assertEquals("vision-ocr", retry.get("source"));
        assertEquals("ahora si funciono", retry.get("text"));
        assertEquals(1, succeedingVision.callCount(), "a failed attempt must not poison the cache for the retry");
    }

    @Test
    void differentObjectsWithDifferentContentGetIndependentCacheEntries() throws IOException {
        FakeS3Client s3 = new FakeS3Client();
        s3.putObject("uploads/a.pdf", buildBlankPdf(1));
        s3.putObject("uploads/b.pdf", buildBlankPdf(2)); // different byte content -> different ETag
        FakeDynamoDbClient dynamoDb = new FakeDynamoDbClient();

        FakeVisionTranscriptionClient visionA = FakeVisionTranscriptionClient.returning("texto A");
        new ExtractTextHandler(s3, visionA, new OcrTextCache(dynamoDb, "leaselens-analyses-test"))
                .handleRequest(Map.of("objectKey", "uploads/a.pdf"), null);

        FakeVisionTranscriptionClient visionB = FakeVisionTranscriptionClient.returning("texto B");
        Map<String, Object> responseB = new ExtractTextHandler(s3, visionB, new OcrTextCache(dynamoDb, "leaselens-analyses-test"))
                .handleRequest(Map.of("objectKey", "uploads/b.pdf"), null);

        assertEquals("vision-ocr", responseB.get("source"), "a genuinely different object must not hit another object's cache entry");
        assertEquals("texto B", responseB.get("text"));
        assertEquals(1, visionB.callCount());
    }

    @Test
    void objectOverTheSizeGuardIsRejectedBeforeParsingOrCallingVision() {
        FakeS3Client s3 = new FakeS3Client();
        // Small actual bytes, but a declared Content-Length over the guard -- exercises the
        // rejection without allocating a real 10MB+ array in the test.
        s3.putObjectWithDeclaredSize("uploads/huge.pdf", new byte[]{1, 2, 3},
                ExtractTextHandler.MAX_OBJECT_SIZE_BYTES + 1);
        FakeVisionTranscriptionClient vision = FakeVisionTranscriptionClient.returning("should never be called");
        ExtractTextHandler handler = new ExtractTextHandler(s3, vision, newCache());

        Map<String, Object> response = handler.handleRequest(Map.of("objectKey", "uploads/huge.pdf"), null);

        assertEquals("too-large", response.get("source"));
        assertEquals("", response.get("text"));
        assertEquals(0, vision.callCount());
    }

    @Test
    void scannedDocumentOverTheOcrPageGuardIsRejectedWithoutCallingVision() throws IOException {
        FakeS3Client s3 = new FakeS3Client();
        s3.putObject("uploads/toolong.pdf", buildBlankPdf(ExtractTextHandler.MAX_OCR_PAGES + 1));
        FakeVisionTranscriptionClient vision = FakeVisionTranscriptionClient.returning("should never be called");
        ExtractTextHandler handler = new ExtractTextHandler(s3, vision, newCache());

        Map<String, Object> response = handler.handleRequest(Map.of("objectKey", "uploads/toolong.pdf"), null);

        assertEquals("too-many-pages", response.get("source"));
        assertEquals("", response.get("text"));
        assertEquals(0, vision.callCount());
    }

    private static OcrTextCache newCache() {
        return new OcrTextCache(new FakeDynamoDbClient(), "leaselens-analyses-test");
    }

    private static byte[] buildPdfWithText(String line) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 12);
                contentStream.newLineAtOffset(50, 700);
                contentStream.showText(line);
                contentStream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildBlankPdf(int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
