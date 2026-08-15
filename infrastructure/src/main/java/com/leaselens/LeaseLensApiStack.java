package com.leaselens;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

/**
 * Exposes the analysis pipeline over HTTP: {@code POST /upload-url} (presigned S3 PUT, T4) and
 * {@code POST /analyze} (orchestration, T8/T9).
 */
public class LeaseLensApiStack extends Stack {

    /**
     * SSM parameter holding the OpenRouter API key. Created OUTSIDE CloudFormation
     * (see docs/DEPLOYMENT.md) as a {@code SecureString}, so CDK never sees or overwrites it.
     * OpenRouter is a single-key router in front of many providers' models (Anthropic, OpenAI,
     * free community models, etc.) — see {@code OpenRouterAnalysisService}.
     */
    private static final String OPENROUTER_API_KEY_PARAM = "/leaselens/openrouter-api-key";

    /** Plain-String SSM parameter for the OpenRouter model id — changeable without redeploying. */
    private static final String OPENROUTER_MODEL_PARAM = "/leaselens/openrouter-model";

    /**
     * Plain-String SSM parameter for the OpenRouter *vision* model id, used by extract-text's
     * T6 OCR fallback ({@code OpenRouterVisionClient}) — independent from {@code
     * OPENROUTER_MODEL_PARAM} since transcription and structured JSON analysis have different
     * cost/quality tradeoffs. Shares the same API key.
     */
    private static final String OPENROUTER_VISION_MODEL_PARAM = "/leaselens/openrouter-vision-model";

    public LeaseLensApiStack(final Construct scope, final String id, final StackProps props,
                              final LeaseLensStorageStack storageStack) {
        super(scope, id, props);

        // Lambda: extract-text — internal only, invoked Lambda-to-Lambda by analyze-contract,
        // never exposed via API Gateway. Timeout/memory sized for the T6 vision-model OCR
        // fallback (PDF page rendering + one OpenRouter HTTP call, itself timed out at 60s
        // client-side), not just the text-layer path.
        Function extractTextFn = Function.Builder.create(this, "ExtractTextFn")
                .functionName("leaselens-extract-text")
                .runtime(Runtime.JAVA_21)
                .handler("com.leaselens.ExtractTextHandler::handleRequest")
                .code(Code.fromAsset("../functions/extract-text/target/extract-text.jar"))
                .memorySize(1536)
                .timeout(Duration.seconds(90))
                .environment(Map.of(
                        "BUCKET_NAME", storageStack.getContractUploadsBucket().getBucketName(),
                        // Shares analyze-contract's DynamoDB table: OcrTextCache stores OCR'd
                        // text keyed by the S3 object's own ETag (an "ocr#"-prefixed partition
                        // key, distinguished from analyze-contract's raw content-hash keys) so a
                        // retry of the exact same upload reuses the same transcription instead
                        // of risking a different one from the non-deterministic vision model.
                        "TABLE_NAME", storageStack.getAnalysesTable().getTableName(),
                        // Secret (SecureString) — fetched at runtime via SSM SDK, never in plain env vars.
                        "OPENROUTER_API_KEY_PARAM", OPENROUTER_API_KEY_PARAM,
                        // Model config (String) — fetched at runtime so it's changeable without redeploy.
                        "OPENROUTER_VISION_MODEL_PARAM", OPENROUTER_VISION_MODEL_PARAM
                ))
                .build();

        storageStack.getContractUploadsBucket().grantRead(extractTextFn);
        storageStack.getAnalysesTable().grantReadWriteData(extractTextFn);

        // T6: extract-text's OCR fallback (OpenRouterVisionClient) needs to read the OpenRouter
        // API key and vision-model config from SSM — same grant shape as analyze-contract below.
        // No Textract IAM here: Textract has no regional endpoint in sa-east-1, where this stack
        // is deployed, so OCR goes through OpenRouter's vision models instead of an AWS OCR
        // service (see the plan doc's T6 status note for the full rationale).
        String extractTextSsmBase = "arn:aws:ssm:" + getRegion() + ":" + getAccount() + ":parameter";
        extractTextFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("ssm:GetParameter"))
                .resources(List.of(
                        extractTextSsmBase + OPENROUTER_API_KEY_PARAM,
                        extractTextSsmBase + OPENROUTER_VISION_MODEL_PARAM
                ))
                .build());
        extractTextFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("kms:Decrypt", "kms:GenerateDataKey"))
                .resources(List.of("arn:aws:kms:" + getRegion() + ":" + getAccount() + ":alias/aws/ssm"))
                .build());

        // Lambda: generate-upload-url — public entry point for browsers to get a presigned S3
        // PUT URL (T4), so uploads never proxy bytes through the backend.
        Function generateUploadUrlFn = Function.Builder.create(this, "GenerateUploadUrlFn")
                .functionName("leaselens-generate-upload-url")
                .runtime(Runtime.JAVA_21)
                .handler("com.leaselens.GenerateUploadUrlHandler::handleRequest")
                .code(Code.fromAsset("../functions/generate-upload-url/target/generate-upload-url.jar"))
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .environment(Map.of(
                        "BUCKET_NAME", storageStack.getContractUploadsBucket().getBucketName()
                ))
                .build();

        // The presigned URL is signed with this Lambda's own credentials, so S3 checks this
        // grant (not the browser's) when the presigned PUT actually happens.
        storageStack.getContractUploadsBucket().grantPut(generateUploadUrlFn);

        // Lambda: analyze-contract — the public orchestration entry point.
        Function analyzeContractFn = Function.Builder.create(this, "AnalyzeContractFn")
                .functionName("leaselens-analyze-contract")
                .runtime(Runtime.JAVA_21)
                .handler("com.leaselens.AnalyzeContractHandler::handleRequest")
                .code(Code.fromAsset("../functions/analyze-contract/target/analyze-contract.jar"))
                .memorySize(512)
                // Covers a full scanned-contract chain: Lambda-to-Lambda call to extract-text
                // (up to its own 90s timeout for the T6 vision-OCR fallback) plus this Lambda's
                // own OpenRouter analysis call (client-side timeout 60s), with headroom.
                .timeout(Duration.seconds(180))
                .environment(Map.of(
                        "TABLE_NAME", storageStack.getAnalysesTable().getTableName(),
                        "EXTRACT_TEXT_FUNCTION_NAME", extractTextFn.getFunctionName(),
                        // Secret (SecureString) — fetched at runtime via SSM SDK, never in plain env vars.
                        "OPENROUTER_API_KEY_PARAM", OPENROUTER_API_KEY_PARAM,
                        // Model config (String) — fetched at runtime so it's changeable without redeploy.
                        "OPENROUTER_MODEL_PARAM", OPENROUTER_MODEL_PARAM
                ))
                .build();

        storageStack.getAnalysesTable().grantReadWriteData(analyzeContractFn);
        extractTextFn.grantInvoke(analyzeContractFn);

        // Allow analyze-contract to read the externally-managed SecureString OpenRouter key and
        // decrypt it, plus the plain-String model config parameter.
        String ssmBase = "arn:aws:ssm:" + getRegion() + ":" + getAccount() + ":parameter";
        analyzeContractFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("ssm:GetParameter"))
                .resources(List.of(
                        ssmBase + OPENROUTER_API_KEY_PARAM,
                        ssmBase + OPENROUTER_MODEL_PARAM
                ))
                .build());
        analyzeContractFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("kms:Decrypt", "kms:GenerateDataKey"))
                .resources(List.of("arn:aws:kms:" + getRegion() + ":" + getAccount() + ":alias/aws/ssm"))
                .build());

        // API Gateway with CORS preflight for the browser PWA.
        RestApi api = RestApi.Builder.create(this, "LeaseLensApi")
                .restApiName("leaselens-api")
                .description("LeaseLens rental contract analyzer REST API")
                .defaultCorsPreflightOptions(CorsOptions.builder()
                        .allowOrigins(Cors.ALL_ORIGINS)
                        .allowMethods(List.of("POST", "OPTIONS"))
                        .allowHeaders(List.of("Content-Type"))
                        .build())
                // T13 abuse guard: caps request rate across the whole API (both routes share
                // this account-wide-per-stage limit, since there's no auth to throttle per-user
                // by). Sized well above any real single user's usage, but low enough to blunt a
                // scripted flood that would otherwise run up OpenRouter/Lambda cost. 5 req/s
                // sustained, bursts up to 10 -- generous for the "one person, one contract"
                // anonymous-tool usage pattern this product targets.
                .deployOptions(StageOptions.builder()
                        .throttlingRateLimit(5)
                        .throttlingBurstLimit(10)
                        .build())
                .build();

        Resource uploadUrlResource = api.getRoot().addResource("upload-url");
        uploadUrlResource.addMethod("POST", new LambdaIntegration(generateUploadUrlFn));

        Resource analyzeResource = api.getRoot().addResource("analyze");
        analyzeResource.addMethod("POST", new LambdaIntegration(analyzeContractFn));
    }
}
