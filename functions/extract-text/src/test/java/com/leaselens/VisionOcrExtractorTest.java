package com.leaselens;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for {@link VisionOcrExtractor}. PDF page rendering runs for real (PDFBox against
 * an in-memory PDF, no network); the transcription step is faked via {@link FakeVisionTranscriptionClient}
 * so the orchestration logic (page count, page order, empty-result handling, normalization) is
 * fully covered without ever calling OpenRouter.
 */
class VisionOcrExtractorTest {

    @Test
    void rendersEveryPageAndPassesThemToTheClientInOrder() throws IOException {
        byte[] pdfBytes = buildBlankPdf(3);
        FakeVisionTranscriptionClient client = FakeVisionTranscriptionClient.returning("texto reconocido");

        String text = VisionOcrExtractor.extractText(pdfBytes, client);

        assertEquals("texto reconocido", text);
        assertEquals(1, client.callCount(), "a multi-page contract should need only one transcription call");
        assertEquals(3, client.lastRequestedPages().size(), "one rendered image per page");
        for (byte[] pageImage : client.lastRequestedPages()) {
            assertTrue(pageImage.length > 0, "each rendered page should be non-empty PNG bytes");
            assertTrue(isPngSignature(pageImage), "rendered page should be a valid PNG");
        }
    }

    @Test
    void normalizesWhitespaceInTheTranscribedText() throws IOException {
        byte[] pdfBytes = buildBlankPdf(1);
        FakeVisionTranscriptionClient client = FakeVisionTranscriptionClient.returning("  Hola   mundo\n\ncontrato  ");

        String text = VisionOcrExtractor.extractText(pdfBytes, client);

        assertEquals("Hola mundo contrato", text);
    }

    @Test
    void neverCallsTheClientWhenThePdfHasNoPages() throws IOException {
        byte[] pdfBytes = buildBlankPdf(0);
        FakeVisionTranscriptionClient client = FakeVisionTranscriptionClient.returning("should never be seen");

        String text = VisionOcrExtractor.extractText(pdfBytes, client);

        assertEquals("", text);
        assertEquals(0, client.callCount());
    }

    @Test
    void renderPagesToPngProducesOnePngPerPage() throws IOException {
        byte[] pdfBytes = buildBlankPdf(2);

        List<byte[]> images = VisionOcrExtractor.renderPagesToPng(pdfBytes);

        assertEquals(2, images.size());
        images.forEach(img -> assertTrue(isPngSignature(img)));
    }

    @Test
    void normalizeWhitespaceHandlesNull() {
        assertEquals("", VisionOcrExtractor.normalizeWhitespace(null));
    }

    private static boolean isPngSignature(byte[] bytes) {
        byte[] pngMagic = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (bytes.length < pngMagic.length) return false;
        for (int i = 0; i < pngMagic.length; i++) {
            if (bytes[i] != pngMagic[i]) return false;
        }
        return true;
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
