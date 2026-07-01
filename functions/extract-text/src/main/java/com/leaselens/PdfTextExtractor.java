package com.leaselens;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;

/**
 * Core "bytes-in, result-out" PDF text extraction logic, kept separate from the Lambda
 * handler so it can be unit tested offline without any AWS dependency.
 */
public final class PdfTextExtractor {

    /**
     * Minimum amount of normalized text (in characters) we require before we trust that
     * the PDF has a real text layer. Scanned/photographed PDFs typically extract to an
     * empty or near-empty string (stray whitespace, form artifacts, etc.), so anything
     * under this threshold is treated as "no usable text layer" and flagged for OCR.
     */
    private static final int MIN_TEXT_LENGTH = 20;

    private PdfTextExtractor() {
    }

    /**
     * Extracts and normalizes text from the given PDF bytes.
     *
     * @param pdfBytes raw bytes of the PDF file
     * @return the extraction result, including a heuristic flag for whether a usable
     *         text layer was found
     */
    public static ExtractionResult extract(byte[] pdfBytes) {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            int pageCount = document.getNumberOfPages();

            PDFTextStripper stripper = new PDFTextStripper();
            String rawText = stripper.getText(document);
            String normalizedText = normalizeWhitespace(rawText);

            boolean hasTextLayer = normalizedText.length() >= MIN_TEXT_LENGTH;
            // T6 (later task) will add OCR fallback and a "source": "ocr" case when
            // hasTextLayer is false here.
            String source = hasTextLayer ? "text-layer" : "none";

            return new ExtractionResult(normalizedText, pageCount, hasTextLayer, source);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse PDF content", e);
        }
    }

    /**
     * Collapses runs of whitespace (spaces, tabs, newlines) into single spaces and trims
     * the result.
     */
    private static String normalizeWhitespace(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    /**
     * Result of extracting text from a PDF's raw bytes.
     */
    public record ExtractionResult(String text, int pageCount, boolean hasTextLayer, String source) {
    }
}
