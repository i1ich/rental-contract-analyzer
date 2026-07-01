package com.leaselens.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leaselens.model.AnalysisResult;
import com.leaselens.model.Finding;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls the Anthropic Messages API to analyze rental contract text against a checklist of
 * common abusive/risky clauses seen in Uruguayan residential rental contracts.
 *
 * API key and model name are read once from SSM at cold start and cached for the lifetime of
 * the execution environment (mirrors VisionService's caching pattern in photolist-latam).
 */
public class ClaudeAnalysisService implements ContractAnalysisService {

    private static final String ANTHROPIC_MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String DEFAULT_MODEL = "claude-sonnet-5";
    private static final int MAX_TOKENS = 4096;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // MVP placeholder prompt — T7 will replace this with a validated, golden-set-tested version.
    private static final String SYSTEM_PROMPT = """
            Sos un asistente legal que ayuda a inquilinos en Montevideo, Uruguay, a entender \
            contratos de alquiler de vivienda. Vas a recibir el texto completo de un contrato \
            de alquiler y debés identificar cláusulas riesgosas o abusivas para el inquilino.

            Prestá especial atención a estos problemas típicos en contratos de alquiler \
            uruguayos:
            - Depósito de garantía excesivo o con condiciones ilegales de devolución.
            - Cláusulas de renovación automática silenciosa (sin aviso claro al inquilino).
            - Obligaciones de mantenimiento y reparaciones trasladadas enteramente al inquilino, \
            incluso por desgaste normal o fallas estructurales.
            - Multas o penalidades abusivas por rescisión anticipada del contrato.
            - Exigencias desproporcionadas sobre el garante o fiador (garantía/fiador), como \
            responsabilidad ilimitada en el tiempo o montos excesivos.

            Respondé ÚNICAMENTE con JSON válido, sin texto adicional ni comentarios, con este \
            formato exacto:
            {
              "summary": "string, 2-3 oraciones en español",
              "findings": [
                {
                  "severity": "red" | "yellow" | "green",
                  "clauseQuote": "texto copiado literalmente del contrato",
                  "location": "string, ej. referencia de página o sección",
                  "plainExplanation": "string en español, explicación en lenguaje simple",
                  "whyItMatters": "string en español, por qué le importa al inquilino"
                }
              ]
            }

            Es OBLIGATORIO que "clauseQuote" sea una cita textual, copiada exactamente del \
            contrato que se te proporciona. Nunca inventes cláusulas ni cites texto que no \
            esté presente en el contrato original.
            """;

    // Cached at cold-start; volatile ensures visibility across threads inside the same container.
    private static volatile String cachedApiKey;
    private static volatile String cachedModel;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public AnalysisResult analyze(String contractText) {
        String requestBody;
        try {
            requestBody = buildRequestBody(contractText);
        } catch (IOException e) {
            throw new RuntimeException("Failed to build Claude request", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_MESSAGES_URL))
                .timeout(Duration.ofSeconds(60))
                .header("x-api-key", getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Claude API request failed: " + e.getMessage());
        } catch (IOException e) {
            throw new ServiceException("Claude API request failed: " + e.getMessage());
        }

        if (response.statusCode() != 200) {
            // Do not leak contract text into error messages/logs — only status code and
            // the (contract-text-free) API error body are included.
            throw new ServiceException("Claude API error: HTTP " + response.statusCode());
        }

        return parseAnalysisResult(response.body());
    }

    private static String getApiKey() {
        if (cachedApiKey != null) return cachedApiKey;
        synchronized (ClaudeAnalysisService.class) {
            if (cachedApiKey != null) return cachedApiKey;
            String paramName = System.getenv("CLAUDE_API_KEY_PARAM");
            if (paramName == null || paramName.isBlank()) {
                throw new IllegalStateException("CLAUDE_API_KEY_PARAM env var not set");
            }
            try (SsmClient ssm = SsmClient.create()) {
                cachedApiKey = ssm.getParameter(GetParameterRequest.builder()
                                .name(paramName).withDecryption(true).build())
                        .parameter().value();
            }
            if (cachedApiKey == null || cachedApiKey.isBlank()) {
                throw new IllegalStateException("Claude API key SSM param is empty: " + paramName);
            }
            return cachedApiKey;
        }
    }

    /** Reads model name from SSM; falls back to DEFAULT_MODEL if param is absent or blank. */
    private static String getModel() {
        if (cachedModel != null) return cachedModel;
        synchronized (ClaudeAnalysisService.class) {
            if (cachedModel != null) return cachedModel;
            String paramName = System.getenv("CLAUDE_MODEL_PARAM");
            cachedModel = readSsmString(paramName, DEFAULT_MODEL);
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

    private String buildRequestBody(String contractText) throws IOException {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "user");
        message.put("content", contractText);

        ArrayNode messages = MAPPER.createArrayNode();
        messages.add(message);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", getModel());
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", SYSTEM_PROMPT);
        body.set("messages", messages);

        return MAPPER.writeValueAsString(body);
    }

    private AnalysisResult parseAnalysisResult(String responseBody) {
        String assistantText;
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode contentArray = root.path("content");
            assistantText = null;
            if (contentArray.isArray()) {
                for (JsonNode block : contentArray) {
                    if ("text".equals(block.path("type").asText())) {
                        assistantText = block.path("text").asText(null);
                        break;
                    }
                }
            }
            if (assistantText == null || assistantText.isBlank()) {
                throw new ServiceException("Claude response missing text content block");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (IOException e) {
            throw new ServiceException("Failed to parse Claude response envelope: " + e.getMessage());
        }

        String json = extractJsonPayload(assistantText);
        try {
            JsonNode node = MAPPER.readTree(json);
            String summary = node.path("summary").asText("");

            List<Finding> findings = new ArrayList<>();
            JsonNode findingsNode = node.path("findings");
            if (findingsNode.isArray()) {
                for (JsonNode f : findingsNode) {
                    Finding finding = new Finding();
                    finding.setSeverity(f.path("severity").asText(null));
                    finding.setClauseQuote(f.path("clauseQuote").asText(null));
                    finding.setLocation(f.path("location").asText(null));
                    finding.setPlainExplanation(f.path("plainExplanation").asText(null));
                    finding.setWhyItMatters(f.path("whyItMatters").asText(null));
                    findings.add(finding);
                }
            }

            return new AnalysisResult(summary, findings);
        } catch (IOException e) {
            // Do not include the raw assistant text (which embeds contract content) in the
            // exception message — only note that parsing failed.
            throw new ServiceException("Failed to parse Claude analysis JSON");
        }
    }

    private static String extractJsonPayload(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int closingFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && closingFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, closingFence).trim();
            }
        }
        return trimmed;
    }
}
