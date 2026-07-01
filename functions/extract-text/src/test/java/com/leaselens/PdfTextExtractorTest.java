package com.leaselens;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for {@link PdfTextExtractor}. Builds small PDFs in-memory with PDFBox
 * itself, so no AWS calls or network access are required.
 */
class PdfTextExtractorTest {

    @Test
    void extractsNormalizedTextAndPageCountFromTextPdf() throws IOException {
        byte[] pdfBytes = buildPdfWithText(2, "Hello   World,", "   this   is a lease contract.");

        PdfTextExtractor.ExtractionResult result = PdfTextExtractor.extract(pdfBytes);

        assertEquals(2, result.pageCount());
        assertTrue(result.hasTextLayer());
        assertEquals("text-layer", result.source());
        assertTrue(result.text().contains("Hello World, this is a lease contract."));
        // Whitespace must be collapsed, not just present verbatim.
        assertFalse(result.text().contains("  "));
    }

    @Test
    void blankPdfHasNoTextLayer() throws IOException {
        byte[] pdfBytes = buildBlankPdf(1);

        PdfTextExtractor.ExtractionResult result = PdfTextExtractor.extract(pdfBytes);

        assertEquals(1, result.pageCount());
        assertFalse(result.hasTextLayer());
        assertEquals("none", result.source());
        assertTrue(result.text().isEmpty());
    }

    /**
     * Builds a PDF with the given number of pages, writing each element of {@code lines}
     * as its own line of text on the first page (real line breaks, not embedded "\n"
     * characters, since PDFBox's {@code showText} rejects control characters) and
     * leaving the rest of the pages blank.
     */
    private static byte[] buildPdfWithText(int pageCount, String... lines) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage());
            }

            PDPage firstPage = document.getPage(0);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, firstPage)) {
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 12);
                contentStream.newLineAtOffset(50, 700);
                contentStream.setLeading(14.5f);
                for (String line : lines) {
                    contentStream.showText(line);
                    contentStream.newLine();
                }
                contentStream.endText();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** Builds a PDF with the given number of pages and no text content at all. */
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
