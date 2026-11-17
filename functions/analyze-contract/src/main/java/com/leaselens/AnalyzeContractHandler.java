package com.leaselens;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaselens.model.AnalyzeContractRequest;
import com.leaselens.service.AnalysisJobStore;
import com.leaselens.service.AnalysisWorkerInvoker;
import com.leaselens.service.LicenseStore;
import com.leaselens.service.LambdaAnalysisWorkerInvoker;
import com.leaselens.service.ServiceException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The public HTTP face of contract analysis. Handles both halves of the asynchronous flow:
 *
 * <ul>
 *   <li>{@code POST /analyze} — accepts an {@code objectKey}, registers a job, kicks off
 *       {@link AnalyzeWorkerHandler} in the background and returns {@code 202} with a
 *       {@code jobId} in milliseconds.</li>
 *   <li>{@code GET /analyze/{jobId}} — reports {@code pending}, the finished analysis, or the
 *       recorded failure.</li>
 * </ul>
 *
 * <p>This endpoint used to do the analysis inline and return the result. It could not: API
 * Gateway caps a REST integration at a hard, unconfigurable 29 seconds and a real analysis takes
 * ~83-92s on the model that passes the T12 quality gate, so every cache-miss request returned 504
 * to the user while the Lambda quietly finished and cached the result. That is not a model-choice
 * problem — the fastest quality-tested candidate still needed ~30-39s — so the request path itself
 * had to stop waiting. See the plan doc's T16 status note.
 *
 * <p>Both routes are served by one Lambda rather than two so a polling client keeps hitting the
 * same warm execution environment instead of paying a Java cold start on each poll.
 */
public class AnalyzeContractHandler implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnalysisJobStore jobStore;
    private final AnalysisWorkerInvoker workerInvoker;
    private final LicenseStore licenseStore;
    private final boolean licenseRequired;

    /** No-arg constructor used by the Lambda runtime: wires up real AWS clients. */
    public AnalyzeContractHandler() {
        this(DynamoDbClient.create(), new LambdaAnalysisWorkerInvoker());
    }

    /**
     * Package-private constructor for tests: allows injecting fakes for all AWS-touching seams.
     * License enforcement follows the {@code LICENSE_REQUIRED} environment variable (off unless it
     * is {@code "true"}), so the flow keeps working unchanged wherever no keys are issued.
     */
    AnalyzeContractHandler(DynamoDbClient dynamoDb, AnalysisWorkerInvoker workerInvoker) {
        this(dynamoDb, workerInvoker, "true".equalsIgnoreCase(System.getenv("LICENSE_REQUIRED")));
    }

    AnalyzeContractHandler(DynamoDbClient dynamoDb, AnalysisWorkerInvoker workerInvoker,
                           boolean licenseRequired) {
        String tableName = System.getenv("TABLE_NAME");
        this.jobStore = new AnalysisJobStore(dynamoDb, tableName);
        this.licenseStore = new LicenseStore(dynamoDb, tableName);
        this.workerInvoker = workerInvoker;
        this.licenseRequired = licenseRequired;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        // Lambda reuses threads across invocations, so never inherit the previous request's id.
        Log.clearCorrelationId();
        try {
            String method = event == null || event.getHttpMethod() == null
                    ? "POST" : event.getHttpMethod().toUpperCase();
            return "GET".equals(method) ? getJobStatus(event) : startJob(event);
        } catch (ServiceException e) {
            Log.warn("request_failed", "status", 502, "reason", e.getMessage());
            return jsonResponse(502, Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            Log.warn("request_rejected", "status", 400, "reason", e.getMessage());
            return jsonResponse(400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            // Never leak raw contract text into logs or error bodies: exception class, message
            // and a compacted stack only — the same discipline as before, now on one line so the
            // trace stays attached to the event instead of becoming one log record per frame.
            Log.error("unexpected_error", e);
            return jsonResponse(500, Map.of("error", "Error interno del servidor"));
        }
    }

    /** {@code POST /analyze} — register the job, start the worker, answer immediately. */
    private APIGatewayProxyResponseEvent startJob(APIGatewayProxyRequestEvent event) {
        AnalyzeContractRequest request = parseRequest(event);
        if (request.getObjectKey() == null || request.getObjectKey().isBlank()) {
            Log.warn("request_rejected", "status", 400, "reason", "objectKey is required");
            return jsonResponse(400, Map.of("error", "objectKey is required"));
        }

        // T18 gate: one cheap read, before any job record, OCR or LLM spend. Nothing is charged
        // here — the worker spends a use only once the analysis has actually succeeded.
        String licenseKey = null;
        if (licenseRequired) {
            licenseKey = LicenseStore.normalize(request.getLicenseKey());
            APIGatewayProxyResponseEvent rejection = checkLicense(licenseKey);
            if (rejection != null) {
                return rejection;
            }
        }

        String jobId = UUID.randomUUID().toString();
        // From here on every line of this invocation carries the jobId, which is also what the
        // worker Lambda stamps its own lines with — the two log groups join on this field.
        Log.setCorrelationId(jobId);
        // Written before the invoke so a status poll that races ahead of the worker finds PENDING
        // rather than a 404 that the client would reasonably read as "this job never existed".
        jobStore.createPending(jobId);
        workerInvoker.startAnalysis(jobId, request.getObjectKey().trim(), licenseKey);

        Log.event("job_accepted", "objectKey", request.getObjectKey().trim());
        return jsonResponse(202, Map.of("jobId", jobId, "status", "pending"));
    }

    /** Returns the response that turns this key away, or {@code null} if it may proceed. */
    private APIGatewayProxyResponseEvent checkLicense(String licenseKey) {
        if (licenseKey == null || licenseKey.isEmpty()) {
            Log.warn("license_rejected", "status", 401, "reason", "missing");
            return jsonResponse(401, Map.of("error", "Ingresá tu clave de acceso para analizar un contrato."));
        }
        LicenseStore.License license = licenseStore.find(licenseKey);
        if (license == null) {
            Log.warn("license_rejected", "status", 403, "reason", "unknown");
            return jsonResponse(403, Map.of("error", "La clave de acceso no es válida."));
        }
        if (license.status() == LicenseStore.Status.REVOKED) {
            Log.warn("license_rejected", "status", 403, "reason", "revoked");
            return jsonResponse(403, Map.of("error", "Esta clave de acceso ya no está activa."));
        }
        if (!license.isUsable()) {
            Log.warn("license_rejected", "status", 403, "reason", "exhausted");
            return jsonResponse(403, Map.of("error", "Esta clave de acceso ya usó todos sus análisis."));
        }
        return null;
    }

    /** {@code GET /analyze/{jobId}} — report progress, the result, or the recorded failure. */
    private APIGatewayProxyResponseEvent getJobStatus(APIGatewayProxyRequestEvent event) {
        Map<String, String> pathParams = event.getPathParameters();
        String jobId = pathParams == null ? null : pathParams.get("jobId");
        if (jobId == null || jobId.isBlank()) {
            Log.warn("request_rejected", "status", 400, "reason", "jobId is required");
            return jsonResponse(400, Map.of("error", "jobId is required"));
        }

        Log.setCorrelationId(jobId);
        AnalysisJobStore.Job job = jobStore.get(jobId);
        if (job == null) {
            Log.warn("job_not_found", "status", 404);
            return jsonResponse(404, Map.of("error",
                    "No encontramos este análisis. Puede haber expirado — probá subir el contrato de nuevo."));
        }

        // Only terminal polls are logged. A client polls every couple of seconds for ~90s, so
        // logging PENDING would add ~45 lines of noise per analysis and bury the two that matter.
        String status = String.valueOf(job.status());
        if (!"PENDING".equals(status)) {
            Log.event("job_poll_terminal", "jobStatus", status,
                    "errorStatusCode", job.errorStatusCode());
        }

        return switch (job.status()) {
            case PENDING -> jsonResponse(200, Map.of("status", "pending"));
            case FAILED -> jsonResponse(200, Map.of(
                    "status", "error",
                    // The HTTP status the old synchronous endpoint would have returned, carried in
                    // the body rather than as the response status: the poll itself succeeded, so a
                    // 4xx/5xx here would conflate "the analysis failed" with "the poll failed".
                    "errorStatusCode", job.errorStatusCode() > 0 ? job.errorStatusCode() : 500,
                    "error", job.errorMessage() == null ? "Error interno del servidor" : job.errorMessage()));
            case DONE -> doneResponse(job);
        };
    }

    private APIGatewayProxyResponseEvent doneResponse(AnalysisJobStore.Job job) {
        if (job.resultJson() == null || job.resultJson().isBlank()) {
            return jsonResponse(500, Map.of("error", "Error interno del servidor"));
        }
        // The stored result is already-serialized JSON; splice it in as a raw value rather than
        // deserializing to AnalysisResult and back, which would be pure overhead and one more
        // place for the shape to drift.
        String remaining = job.remainingUses() == null
                ? "" : ",\"remainingUses\":" + job.remainingUses();
        String body = "{\"status\":\"done\",\"result\":" + job.resultJson() + remaining + "}";
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(corsHeaders())
                .withBody(body);
    }

    private AnalyzeContractRequest parseRequest(APIGatewayProxyRequestEvent event) {
        String body = event == null ? null : event.getBody();
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Request body is required");
        }
        try {
            return MAPPER.readValue(body, AnalyzeContractRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON body");
        }
    }

    private static Map<String, String> corsHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        return headers;
    }

    private APIGatewayProxyResponseEvent jsonResponse(int statusCode, Object body) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withHeaders(corsHeaders())
                    .withBody(MAPPER.writeValueAsString(body));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize response", e);
        }
    }
}
