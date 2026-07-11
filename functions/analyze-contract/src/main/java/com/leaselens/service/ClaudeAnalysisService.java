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
            // Local/dev fallback (used by the T12 golden-set gate): a plain env var beats
            // the SSM round-trip when running outside AWS.
            String envKey = System.getenv("ANTHROPIC_API_KEY");
            if (envKey != null && !envKey.isBlank()) {
                cachedApiKey = envKey;
                return cachedApiKey;
            }
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
