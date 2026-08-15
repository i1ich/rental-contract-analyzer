package com.leaselens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for {@link OpenRouterVisionClient}'s pure request-building and response-parsing
 * logic — the real HTTP round-trip to OpenRouter isn't exercised here (same convention as
 * {@code TextractOcrExtractorTest} not exercising a real Textract job before it), but everything
 * around it is: the exact JSON shape sent, and every response envelope shape it must handle.
 */
class OpenRouterVisionClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildRequestBodyIncludesModelAndOneImageBlockPerPage() throws IOException {
        byte[] page1 = {1, 2, 3};
        byte[] page2 = {4, 5, 6};

        String body = OpenRouterVisionClient.buildRequestBody(List.of(page1, page2), "some/vision-model");

        JsonNode root = MAPPER.readTree(body);
        assertEquals("some/vision-model", root.path("model").asText());
        assertTrue(root.path("max_tokens").asInt() > 0);
        // Deterministic-as-possible transcription so identical scans hit analyze-contract's
        // content-hash cache instead of re-running the full pipeline every time.
        assertEquals(0, root.path("temperature").asInt());

        JsonNode content = root.path("messages").path(0).path("content");
        // One text instruction block plus one image block per page.
        assertEquals(3, content.size());
        assertEquals("text", content.path(0).path("type").asText());

        String expectedImage1 = "data:image/png;base64," + Base64.getEncoder().encodeToString(page1);
        String expectedImage2 = "data:image/png;base64," + Base64.getEncoder().encodeToString(page2);
        assertEquals("image_url", content.path(1).path("type").asText());
        assertEquals(expectedImage1, content.path(1).path("image_url").path("url").asText());
        assertEquals("image_url", content.path(2).path("type").asText());
        assertEquals(expectedImage2, content.path(2).path("image_url").path("url").asText());
    }

    @Test
    void buildRequestBodyWithNoPagesStillProducesTheInstructionBlock() throws IOException {
        String body = OpenRouterVisionClient.buildRequestBody(List.of(), "some/vision-model");

        JsonNode content = MAPPER.readTree(body).path("messages").path(0).path("content");
        assertEquals(1, content.size());
        assertEquals("text", content.path(0).path("type").asText());
    }

    @Test
    void parseTranscriptionExtractsMessageContentFromChatCompletionsEnvelope() {
        String response = """
                {"choices":[{"message":{"role":"assistant","content":"texto del contrato"}}]}
                """;

        String text = OpenRouterVisionClient.parseTranscription(response);

        assertEquals("texto del contrato", text);
    }

    @Test
    void parseTranscriptionThrowsWhenMessageContentIsMissing() {
        String response = """
                {"choices":[{"message":{"role":"assistant"}}]}
                """;

        assertThrows(RuntimeException.class, () -> OpenRouterVisionClient.parseTranscription(response));
    }

    @Test
    void parseTranscriptionThrowsOnMalformedJson() {
        assertThrows(RuntimeException.class, () -> OpenRouterVisionClient.parseTranscription("not json"));
    }
}
