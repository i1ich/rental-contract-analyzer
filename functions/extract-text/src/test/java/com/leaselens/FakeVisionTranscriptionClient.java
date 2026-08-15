package com.leaselens;

import java.util.List;
import java.util.function.Function;

/**
 * Fake {@link VisionTranscriptionClient} for offline tests: returns a canned response (or throws
 * a canned failure) without ever making a network call, and records what it was asked to
 * transcribe so tests can assert on page count/ordering.
 */
public class FakeVisionTranscriptionClient implements VisionTranscriptionClient {

    private final Function<List<byte[]>, String> behavior;
    private List<byte[]> lastRequestedPages;
    private int callCount = 0;

    public static FakeVisionTranscriptionClient returning(String text) {
        return new FakeVisionTranscriptionClient(pages -> text);
    }

    public static FakeVisionTranscriptionClient throwing(RuntimeException exception) {
        return new FakeVisionTranscriptionClient(pages -> {
            throw exception;
        });
    }

    private FakeVisionTranscriptionClient(Function<List<byte[]>, String> behavior) {
        this.behavior = behavior;
    }

    @Override
    public String transcribe(List<byte[]> pageImagesPng) {
        callCount++;
        lastRequestedPages = pageImagesPng;
        return behavior.apply(pageImagesPng);
    }

    public int callCount() {
        return callCount;
    }

    public List<byte[]> lastRequestedPages() {
        return lastRequestedPages;
    }
}
