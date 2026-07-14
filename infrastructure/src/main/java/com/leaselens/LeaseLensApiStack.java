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

    public LeaseLensApiStack(final Construct scope, final String id, final StackProps props,
                              final LeaseLensStorageStack storageStack) {
        super(scope, id, props);

        // Lambda: extract-text — internal only, invoked Lambda-to-Lambda by analyze-contract,
        // never exposed via API Gateway. Timeout/memory sized for the T6 Textract OCR fallback
        // (start job + poll, on top of the S3 read + PDFBox pass), not just the text-layer path.
        Function extractTextFn = Function.Builder.create(this, "ExtractTextFn")
                .functionName("leaselens-extract-text")
                .runtime(Runtime.JAVA_21)
                .handler("com.leaselens.ExtractTextHandler::handleRequest")
                .code(Code.fromAsset("../functions/extract-text/target/extract-text.jar"))
                .memorySize(1024)
                .timeout(Duration.seconds(60))
                .environment(Map.of(
                        "BUCKET_NAME", storageStack.getContractUploadsBucket().getBucketName()
                ))
                .build();

        storageStack.getContractUploadsBucket().grantRead(extractTextFn);

        // T6: extract-text needs to start/poll Textract's async text-detection job. Textract
        // doesn't support resource-level restriction on these actions, hence "*".
        extractTextFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("textract:StartDocumentTextDetection", "textract:GetDocumentTextDetection"))
                .resources(List.of("*"))
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
                .timeout(Duration.seconds(120))
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
                .build();

        Resource uploadUrlResource = api.getRoot().addResource("upload-url");
        uploadUrlResource.addMethod("POST", new LambdaIntegration(generateUploadUrlFn));

        Resource analyzeResource = api.getRoot().addResource("analyze");
        analyzeResource.addMethod("POST", new LambdaIntegration(analyzeContractFn));
    }
}
