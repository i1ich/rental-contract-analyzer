package com.leaselens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Transcribes scanned/photographed contract pages via a vision-capable LLM reached through
 * OpenRouter — the real replacement for AWS Textract (T6), which has no regional endpoint in
 * {@code sa-east-1}. OpenRouter is an outbound HTTPS call regardless of AWS region, so this
 * sidesteps the region problem entirely instead of working around it (e.g. cross-region
 * Textract calls, which would also require staging the S3 object in a second region).
 *
 * <p>Uses its own model config, separate from {@code OpenRouterAnalysisService}'s
 * {@code openrouter-model} param (analyze-contract): transcription and structured JSON analysis
 * have different cost/quality tradeoffs, so they're independently swappable. Both share the same
 * OpenRouter API key.
 */
public class OpenRouterVisionClient implements VisionTranscriptionClient {

    private static final String OPENROUTER_CHAT_URL = "https://openrouter.ai/api/v1/chat/completions";
    // Cheap, fast, strong at reading dense text from photographed documents (large multimodal
    // context window handles several contract pages in one request).
    private static final String DEFAULT_VISION_MODEL = "google/gemini-2.5-flash-lite";
    private static final int MAX_TOKENS = 8000;

    private static final String TRANSCRIBE_INSTRUCTION = """
            Transcribí TEXTUALMENTE todo el texto visible en las siguientes imágenes, que son \
            páginas escaneadas o fotografiadas (en orden) de un contrato de alquiler en español. \
            No traduzcas, no resumas, no corrijas errores ni completes texto que no se vea con \
            claridad — si una palabra es realmente ilegible, escribí [ilegible] en su lugar. \
            Respondé ÚNICAMENTE con el texto transcripto de todas las páginas en orden, sin \
            comentarios, encabezados ni marcas de página adicionales.
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Cached at cold-start; volatile ensures visibility across threads inside the same container.
    private static volatile String cachedApiKey;
    private static volatile String cachedModel;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String transcribe(List<byte[]> pageImagesPng) {
        String requestBody;
        try {
            requestBody = buildRequestBody(pageImagesPng, getModel());
        } catch (IOException e) {
            throw new RuntimeException("Failed to build OpenRouter vision request", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENROUTER_CHAT_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + getApiKey())
                .header("Content-Type", "application/json")
                // Optional but recommended by OpenRouter for attribution/rankings; harmless if ignored.
                .header("HTTP-Referer", "https://github.com/i1ich/rental-contract-analyzer")
                .header("X-Title", "LeaseLens")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenRouter vision request failed: " + e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException("OpenRouter vision request failed: " + e.getMessage());
        }

        if (response.statusCode() != 200) {
            // Do not leak image/contract content into error messages/logs — only the status code.
            throw new RuntimeException("OpenRouter vision API error: HTTP " + response.statusCode());
        }

        return parseTranscription(response.body());
    }

    /** Builds the OpenAI-compatible multimodal chat-completion request body for the given pages. */
    static String buildRequestBody(List<byte[]> pageImagesPng, String model) throws IOException {
        ObjectNode textBlock = MAPPER.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", TRANSCRIBE_INSTRUCTION);

        ArrayNode contentBlocks = MAPPER.createArrayNode();
        contentBlocks.add(textBlock);
        for (byte[] pageImage : pageImagesPng) {
            ObjectNode imageBlock = MAPPER.createObjectNode();
            imageBlock.put("type", "image_url");
            ObjectNode imageUrl = MAPPER.createObjectNode();
            imageUrl.put("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(pageImage));
            imageBlock.set("image_url", imageUrl);
            contentBlocks.add(imageBlock);
        }

        ObjectNode userMessage = MAPPER.createObjectNode();
        userMessage.put("role", "user");
        userMessage.set("content", contentBlocks);

        ArrayNode messages = MAPPER.createArrayNode();
        messages.add(userMessage);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        // Transcription should be as deterministic as possible: analyze-contract caches by a
        // hash of the extracted text, so a differently-worded transcription of the exact same
        // scanned page defeats that cache and forces a full re-analysis every time. temperature=0
        // doesn't guarantee bit-for-bit identical output, but reduces run-to-run drift a lot.
        body.put("temperature", 0);
        body.set("messages", messages);

        return MAPPER.writeValueAsString(body);
    }

    /** Extracts the assistant's transcribed text from an OpenRouter chat-completions response body. */
    static String parseTranscription(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode firstChoice = root.path("choices").path(0);
            String text = firstChoice.path("message").path("content").asText(null);
            if (text == null) {
                throw new IllegalStateException("OpenRouter vision response missing message content");
            }
            return text;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OpenRouter vision response envelope: " + e.getMessage());
        }
    }

    private static String getApiKey() {
        if (cachedApiKey != null) return cachedApiKey;
        synchronized (OpenRouterVisionClient.class) {
            if (cachedApiKey != null) return cachedApiKey;
            String envKey = System.getenv("OPENROUTER_API_KEY");
            if (envKey != null && !envKey.isBlank()) {
                cachedApiKey = envKey;
                return cachedApiKey;
            }
            String paramName = System.getenv("OPENROUTER_API_KEY_PARAM");
            if (paramName == null || paramName.isBlank()) {
                throw new IllegalStateException("OPENROUTER_API_KEY_PARAM env var not set");
            }
            try (SsmClient ssm = SsmClient.create()) {
                cachedApiKey = ssm.getParameter(GetParameterRequest.builder()
                                .name(paramName).withDecryption(true).build())
                        .parameter().value();
            }
            if (cachedApiKey == null || cachedApiKey.isBlank()) {
                throw new IllegalStateException("OpenRouter API key SSM param is empty: " + paramName);
            }
            return cachedApiKey;
        }
    }

    private static String getModel() {
        if (cachedModel != null) return cachedModel;
        synchronized (OpenRouterVisionClient.class) {
            if (cachedModel != null) return cachedModel;
            String envModel = System.getenv("OPENROUTER_VISION_MODEL");
            if (envModel != null && !envModel.isBlank()) {
                cachedModel = envModel;
                return cachedModel;
            }
            String paramName = System.getenv("OPENROUTER_VISION_MODEL_PARAM");
            cachedModel = readSsmString(paramName, DEFAULT_VISION_MODEL);
            return cachedModel;
        }
    }

    /** Helper: reads a plain-String SSM parameter; returns {@code fallback} on any error. */
    private static String readSsmString(String paramName, String fallback) {
        if (paramName == null || paramName.isBlank()) return fallback;
        try (SsmClient ssm = SsmClient.create()) {
            String value = ssm.getParameter(GetParameterRequest.builder()
                            .name(paramName).withDecryption(false).build())
                    .parameter().value();
            return (value == null || value.isBlank()) ? fallback : value;
        } catch (Exception e) {
            // Parameter may not exist yet during local testing — use default.
            return fallback;
        }
    }
}
