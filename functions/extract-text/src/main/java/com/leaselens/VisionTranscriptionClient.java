package com.leaselens;

import java.util.List;

/**
 * Seam for transcribing rendered contract-page images into text, so {@link VisionOcrExtractor}'s
 * orchestration logic (page rendering, ordering, empty-result handling) can be unit tested
 * offline against a fake, without a real network call to a vision model.
 */
public interface VisionTranscriptionClient {

    /** Transcribes the given page images (PNG bytes, one per page, in page order) into text. */
    String transcribe(List<byte[]> pageImagesPng);
}
