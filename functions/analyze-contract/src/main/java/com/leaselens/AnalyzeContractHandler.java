package com.leaselens;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaselens.model.AnalyzeContractRequest;
import com.leaselens.service.AnalysisJobStore;
import com.leaselens.service.AnalysisWorkerInvoker;
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

    /** No-arg constructor used by the Lambda runtime: wires up real AWS clients. */
    public AnalyzeContractHandler() {
        this(DynamoDbClient.create(), new LambdaAnalysisWorkerInvoker());
    }

    /** Package-private constructor for tests: allows injecting fakes for all AWS-touching seams. */
    AnalyzeContractHandler(DynamoDbClient dynamoDb, AnalysisWorkerInvoker workerInvoker) {
        this.jobStore = new AnalysisJobStore(dynamoDb, System.getenv("TABLE_NAME"));
        this.workerInvoker = workerInvoker;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            String method = event == null || event.getHttpMethod() == null
                    ? "POST" : event.getHttpMethod().toUpperCase();
            return "GET".equals(method) ? getJobStatus(event) : startJob(event);
        } catch (ServiceException e) {
            return jsonResponse(502, Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return jsonResponse(400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            // Never leak raw contract text into logs or error bodies.
            System.err.println("Unexpected error [" + e.getClass().getName() + "]: " + e.getMessage());
            e.printStackTrace(System.err);
            return jsonResponse(500, Map.of("error", "Error interno del servidor"));
        }
    }

    /** {@code POST /analyze} — register the job, start the worker, answer immediately. */
    private APIGatewayProxyResponseEvent startJob(APIGatewayProxyRequestEvent event) {
        AnalyzeContractRequest request = parseRequest(event);
        if (request.getObjectKey() == null || request.getObjectKey().isBlank()) {
            return jsonResponse(400, Map.of("error", "objectKey is required"));
        }

        String jobId = UUID.randomUUID().toString();
        // Written before the invoke so a status poll that races ahead of the worker finds PENDING
        // rather than a 404 that the client would reasonably read as "this job never existed".
        jobStore.createPending(jobId);
        workerInvoker.startAnalysis(jobId, request.getObjectKey().trim());

        return jsonResponse(202, Map.of("jobId", jobId, "status", "pending"));
    }

    /** {@code GET /analyze/{jobId}} — report progress, the result, or the recorded failure. */
    private APIGatewayProxyResponseEvent getJobStatus(APIGatewayProxyRequestEvent event) {
        Map<String, String> pathParams = event.getPathParameters();
        String jobId = pathParams == null ? null : pathParams.get("jobId");
        if (jobId == null || jobId.isBlank()) {
            return jsonResponse(400, Map.of("error", "jobId is required"));
        }

        AnalysisJobStore.Job job = jobStore.get(jobId);
        if (job == null) {
            return jsonResponse(404, Map.of("error",
                    "No encontramos este análisis. Puede haber expirado — probá subir el contrato de nuevo."));
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
        String body = "{\"status\":\"done\",\"result\":" + job.resultJson() + "}";
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
