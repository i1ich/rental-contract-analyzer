package com.leaselens;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * OCR fallback for scanned/photographed PDFs (T6): renders every page to a PNG image locally
 * with PDFBox, then asks a {@link VisionTranscriptionClient} (a vision-capable LLM reached via
 * OpenRouter — see {@link OpenRouterVisionClient}) to transcribe them, so downstream code is
 * source-agnostic about whether text came from a native PDF text layer or from OCR.
 *
 * <p>This replaces an earlier AWS Textract-based implementation: Textract has no regional
 * endpoint in {@code sa-east-1} (São Paulo), where this stack is deployed, so any AWS-native OCR
 * service is a dead end here without migrating the whole stack to a different region. Rendering
 * locally with PDFBox and transcribing via an outbound HTTPS call to OpenRouter sidesteps the
 * AWS region entirely — see the plan doc's T6 status note for the full rationale.
 */
public final class VisionOcrExtractor {

    // Balances legibility of a phone-photo page against image size (and therefore base64
    // payload size / vision-model token cost). 150 DPI keeps a typical A4 page under ~2000px
    // on its long edge, which is enough for the model to read normal body text.
    private static final float RENDER_DPI = 150f;

    private VisionOcrExtractor() {
    }

    /**
     * Renders every page of the PDF to a PNG image and asks the given client to transcribe them
     * together (page order preserved), so a multi-page contract needs only one round trip.
     * Returns an empty string if the PDF has no pages.
     */
    public static String extractText(byte[] pdfBytes, VisionTranscriptionClient client) {
        List<byte[]> pageImages = renderPagesToPng(pdfBytes);
        if (pageImages.isEmpty()) {
            return "";
        }
        String transcription = client.transcribe(pageImages);
        return normalizeWhitespace(transcription);
    }

    /** Renders each page of the given PDF to a PNG-encoded image, in page order. */
    static List<byte[]> renderPagesToPng(byte[] pdfBytes) {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            List<byte[]> images = new ArrayList<>(document.getNumberOfPages());
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, RENDER_DPI);
                images.add(toPngBytes(image));
            }
            return images;
        } catch (IOException e) {
            throw new RuntimeException("Failed to render PDF pages for vision OCR", e);
        }
    }

    private static byte[] toPngBytes(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode rendered PDF page as PNG", e);
        }
    }

    /** Same whitespace-collapsing normalization {@link PdfTextExtractor} applies. */
    static String normalizeWhitespace(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ");
    }
}
