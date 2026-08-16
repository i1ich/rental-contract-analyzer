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
 * Calls OpenRouter's OpenAI-compatible chat completions endpoint to analyze rental contract
 * text against a checklist of common abusive/risky clauses seen in Uruguayan residential
 * rental contracts.
 *
 * OpenRouter is a single-key router in front of many providers' models (Anthropic, OpenAI,
 * free community models, etc.) — the model to use is just a config string
 * ({@code "anthropic/claude-sonnet-5"}, {@code "nvidia/nemotron-3-ultra-550b-a55b:free"}, ...),
 * which is exactly the "LLM: swappable, config in SSM" locked decision from the plan.
 *
 * API key and model name are read once from SSM at cold start and cached for the lifetime of
 * the execution environment (mirrors VisionService's caching pattern in photolist-latam).
 */
public class OpenRouterAnalysisService implements ContractAnalysisService {

    private static final String OPENROUTER_CHAT_URL = "https://openrouter.ai/api/v1/chat/completions";
    // Default model routed through OpenRouter, used only when neither OPENROUTER_MODEL (env,
    // local runs) nor OPENROUTER_MODEL_PARAM (SSM) yields a value — i.e. this is the silent
    // fallback for a misconfigured or unreadable SSM param in production, not a normal path.
    // It is therefore deliberately the model this project's own T12 golden-set gate has actually
    // passed on (100% recall, 0 hallucinated quotes, 2026-07-13), so degrading to the fallback
    // degrades cost, never correctness. Was `nvidia/nemotron-3-ultra-550b-a55b:free`, which is
    // disqualified for this fallback: it takes ~85s on a real contract, well past API Gateway's
    // hard, unconfigurable 29s integration timeout, so falling back to it would have turned any
    // SSM read failure into a 504 on every request. Override via the SSM param (or the env var
    // locally) to try other OpenRouter models.
    private static final String DEFAULT_MODEL = "anthropic/claude-sonnet-5";
    // A real Uruguayan rental contract with this checklist commonly needs 5-8K completion
    // tokens for the full findings JSON; 4096 silently truncated mid-JSON on the golden set
    // (finish_reason "length") during the first live T12 run against OpenRouter. 12000 leaves
    // headroom for longer/denser contracts.
    private static final int MAX_TOKENS = 12000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // T7 prompt — checklist grounded in Uruguayan rental law (DL 14.219, Ley 15.056,
    // Ley 19.889/LUC) and validated against the private golden set via
    // GoldenSetValidationGateTest (T12).
    private static final String SYSTEM_PROMPT = """
            Sos un asistente que ayuda a inquilinos en Montevideo, Uruguay, a entender \
            contratos de alquiler de vivienda ANTES de firmarlos o al revisarlos. Recibís el \
            texto completo de un contrato de arrendamiento y devolvés un análisis de cláusulas \
            riesgosas o abusivas para el inquilino, en lenguaje simple. Sos una herramienta \
            educativa, no asesoramiento legal.

            CONTEXTO LEGAL URUGUAYO (usalo para evaluar, citando leyes solo cuando ayude):
            - Decreto-Ley 14.219 (régimen protegido): plazo mínimo de 2 años para vivienda, \
            prórroga legal para el buen pagador, ajuste de precio regulado. Solo aplica a \
            inmuebles con permiso de construcción ANTERIOR al 2/6/1968.
            - Libre contratación (DL 14.219 arts. 2 y 103; Ley 15.056): inmuebles con permiso \
            posterior al 2/6/1968; las partes pactan plazo, precio y ajuste libremente y el \
            inquilino NO tiene las protecciones anteriores. Si el contrato declara este \
            régimen sin indicar la fecha del permiso de construcción, señalalo como punto a \
            verificar.
            - Ley 19.889 (LUC, arrendamiento sin garantía): régimen opcional, solo si consta \
            expresamente y no hay garantía; desalojos más rápidos.
            - Garantías habituales: depósito (tope legal de 5 meses para vivienda bajo \
            14.219), fianza personal, seguro de alquiler (Porto Seguro, Sancor, SBI), ANDA, \
            CGN, Contaduría. Garantías por encima de 5 meses de alquiler son una señal de \
            alerta.
            - Código Civil arts. 1818-1819: las reparaciones por desgaste normal, vicios \
            ocultos y defectos estructurales corresponden al arrendador; el inquilino \
            responde por el mal uso.
            - La mora automática con interés a la tasa máxima del BCU es habitual pero \
            merece mención; un interés rotulado "compensatorio" cuando es punitorio es un \
            error técnico a señalar.

            CHECKLIST DE PROBLEMAS TÍPICOS (buscá cada uno; no inventes los que no estén):
            1. Aceleración de alquileres: pagar "el alquiler restante" del plazo (peor si es \
            "en una sola partida" o "cualquiera sea la causa") al irse antes → ROJO.
            2. Ausencia total de cláusula de salida anticipada en contratos a plazo fijo → \
            ROJO (el inquilino queda atado al plazo completo).
            3. Salida anticipada que depende solo de la voluntad del arrendador \
            ("autorización expresa", inquilino sustituto "aceptable") sin criterio objetivo.
            4. Mantenimiento/reparaciones trasladados al inquilino "en todos los aspectos", \
            por "cualquier deterioro", o desgaste normal / fallas estructurales a su cargo → \
            ROJO.
            5. Arrendador que "no responde" por vicios, defectos o interrupciones de \
            servicios → ROJO.
            6. Tasación de daños o inventario de salida unilateral (administrador, técnico \
            designado por el arrendador, "si el inquilino no coopera").
            7. Trampas de renovación/prórroga: renovación silenciosa si el inquilino no \
            avisa, o pérdida de la opción de prórroga por no avisar dentro de un plazo; \
            renovación condicionada a "no estar en mora" sin umbral mínimo.
            8. Fechas, montos o campos en blanco / sin completar en el documento.
            9. Depósitos o pagos extra atípicos: monto de "últimas facturas" al salir, \
            garantía superior a 5 meses, primera cuota como condición de entrega de llaves \
            sin recibo formal.
            10. Mora automática + interés a tasa máxima BCU sobre "cualquier deuda" \
            (incluyendo reparaciones discutibles).
            11. Responsabilidad solidaria e indivisible entre co-inquilinos (estándar, pero \
            el inquilino debe saberlo).
            12. Acceso del arrendador sin preaviso mínimo para inspección/tasación/venta.
            13. Bienes abandonados que quedan a favor del arrendador (art. 487 C. Civil).
            14. Precio en dólares: riesgo cambiario para quien gana en pesos (legal y común; \
            AMARILLO, no rojo).
            15. Régimen legal declarado ("libre contratación") sin acreditar el supuesto \
            habilitante, o citas legales incorrectas.
            16. Notificaciones asimétricas: el aviso clave del inquilino por canal informal \
            (mail a un tercero) mientras el resto exige telegrama colacionado.

            SEVERIDAD:
            - "red": exposición económica o legal significativa (aceleración de alquileres, \
            renuncia de responsabilidad del arrendador, mantenimiento total al inquilino).
            - "yellow": cláusula desventajosa, ambigua o con plazo/trampa que conviene \
            negociar o agendar.
            - "green": cláusula favorable al inquilino o protección que vale destacar \
            (ej. reparaciones estructurales expresamente a cargo del arrendador).

            CLÁUSULAS AUSENTES: si un problema es la FALTA de una cláusula (ej. sin salida \
            anticipada, sin obligación de reparaciones del arrendador), reportalo igual con \
            "clauseQuote": "" y "location": "ausente en el contrato".

            Respondé ÚNICAMENTE con JSON válido, sin texto adicional ni comentarios, con este \
            formato exacto:
            {
              "summary": "string, 2-3 oraciones en español rioplatense, con el balance general del contrato",
              "findings": [
                {
                  "severity": "red" | "yellow" | "green",
                  "clauseQuote": "texto copiado literalmente del contrato (o \\"\\" si la cláusula está ausente)",
                  "location": "string, ej. sección o cláusula donde aparece",
                  "plainExplanation": "string en español, explicación en lenguaje simple",
                  "whyItMatters": "string en español, por qué le importa al inquilino y qué hacer al respecto"
                }
              ]
            }

            Ordená los findings de mayor a menor severidad. Es OBLIGATORIO que "clauseQuote" \
            sea una cita textual, copiada exactamente del contrato que se te proporciona \
            (misma ortografía, mismos números). Nunca inventes cláusulas ni cites texto que \
            no esté presente en el contrato original. Si el texto no parece un contrato de \
            alquiler, devolvé un summary que lo diga y "findings": [].
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
            throw new RuntimeException("Failed to build OpenRouter request", e);
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
            throw new ServiceException("OpenRouter API request failed: " + e.getMessage());
        } catch (IOException e) {
            throw new ServiceException("OpenRouter API request failed: " + e.getMessage());
        }

        if (response.statusCode() != 200) {
            // Do not leak contract text into error messages/logs — only status code and
            // the (contract-text-free) API error body are included.
            throw new ServiceException("OpenRouter API error: HTTP " + response.statusCode());
        }

        return parseAnalysisResult(response.body());
    }

    private static String getApiKey() {
        if (cachedApiKey != null) return cachedApiKey;
        synchronized (OpenRouterAnalysisService.class) {
            if (cachedApiKey != null) return cachedApiKey;
            // Local/dev fallback (used by the T12 golden-set gate): a plain env var beats
            // the SSM round-trip when running outside AWS.
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

    /**
     * Reads model name from an env var override first (for local runs), then SSM, then
     * falls back to DEFAULT_MODEL if neither is set.
     */
    private static String getModel() {
        if (cachedModel != null) return cachedModel;
        synchronized (OpenRouterAnalysisService.class) {
            if (cachedModel != null) return cachedModel;
            String envModel = System.getenv("OPENROUTER_MODEL");
            if (envModel != null && !envModel.isBlank()) {
                cachedModel = envModel;
                return cachedModel;
            }
            String paramName = System.getenv("OPENROUTER_MODEL_PARAM");
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
        // OpenRouter speaks the OpenAI chat-completions shape: system prompt is its own
        // message (not a top-level field like Anthropic's Messages API).
        ObjectNode systemMessage = MAPPER.createObjectNode();
        systemMessage.put("role", "system");
        systemMessage.put("content", SYSTEM_PROMPT);

        ObjectNode userMessage = MAPPER.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", contractText);

        ArrayNode messages = MAPPER.createArrayNode();
        messages.add(systemMessage);
        messages.add(userMessage);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", getModel());
        body.put("max_tokens", MAX_TOKENS);
        body.set("messages", messages);

        return MAPPER.writeValueAsString(body);
    }

    private AnalysisResult parseAnalysisResult(String responseBody) {
        String assistantText;
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            // OpenAI-compatible chat-completions envelope: choices[0].message.content
            // (Anthropic's Messages API used a top-level "content" array instead).
            JsonNode firstChoice = root.path("choices").path(0);
            assistantText = firstChoice.path("message").path("content").asText(null);
            if (assistantText == null || assistantText.isBlank()) {
                throw new ServiceException("OpenRouter response missing message content");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("Failed to parse OpenRouter response envelope: " + e.getMessage());
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
            throw new ServiceException("Failed to parse OpenRouter analysis JSON");
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
