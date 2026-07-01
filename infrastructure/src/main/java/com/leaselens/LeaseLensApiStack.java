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
 * Exposes the analysis pipeline over HTTP.
 *
 * <p>Only {@code POST /analyze} is wired so far. {@code POST /upload-url} (the
 * generate-upload-url Lambda, T4) has not been built yet — it lands separately and will add a
 * second resource here. Until then, {@code objectKey} passed to {@code /analyze} must come from
 * an object already present in the uploads bucket (e.g. placed there manually for smoke testing;
 * see docs/DEPLOYMENT.md).
 */
public class LeaseLensApiStack extends Stack {

    /**
     * SSM parameter holding the Anthropic (Claude) API key. Created OUTSIDE CloudFormation
     * (see docs/DEPLOYMENT.md) as a {@code SecureString}, so CDK never sees or overwrites it.
     */
    private static final String CLAUDE_API_KEY_PARAM = "/leaselens/claude-api-key";

    /** Plain-String SSM parameter for the Claude model name — changeable without redeploying. */
    private static final String CLAUDE_MODEL_PARAM = "/leaselens/claude-model";

    public LeaseLensApiStack(final Construct scope, final String id, final StackProps props,
                              final LeaseLensStorageStack storageStack) {
        super(scope, id, props);

        // Lambda: extract-text — internal only, invoked Lambda-to-Lambda by analyze-contract,
        // never exposed via API Gateway.
        Function extractTextFn = Function.Builder.create(this, "ExtractTextFn")
                .functionName("leaselens-extract-text")
                .runtime(Runtime.JAVA_21)
                .handler("com.leaselens.ExtractTextHandler::handleRequest")
                .code(Code.fromAsset("../functions/extract-text/target/extract-text.jar"))
                .memorySize(1024)
                .timeout(Duration.seconds(30))
                .environment(Map.of(
                        "BUCKET_NAME", storageStack.getContractUploadsBucket().getBucketName()
                ))
                .build();

        storageStack.getContractUploadsBucket().grantRead(extractTextFn);

        // Lambda: analyze-contract — the public orchestration entry point.
        Function analyzeContractFn = Function.Builder.create(this, "AnalyzeContractFn")
                .functionName("leaselens-analyze-contract")
                .runtime(Runtime.JAVA_21)
                .handler("com.leaselens.AnalyzeContractHandler::handleRequest")
                .code(Code.fromAsset("../functions/analyze-contract/target/analyze-contract.jar"))
                .memorySize(512)
                .timeout(Duration.seconds(60))
                .environment(Map.of(
                        "TABLE_NAME", storageStack.getAnalysesTable().getTableName(),
                        "EXTRACT_TEXT_FUNCTION_NAME", extractTextFn.getFunctionName(),
                        // Secret (SecureString) — fetched at runtime via SSM SDK, never in plain env vars.
                        "CLAUDE_API_KEY_PARAM", CLAUDE_API_KEY_PARAM,
                        // Model config (String) — fetched at runtime so it's changeable without redeploy.
                        "CLAUDE_MODEL_PARAM", CLAUDE_MODEL_PARAM
                ))
                .build();

        storageStack.getAnalysesTable().grantReadWriteData(analyzeContractFn);
        extractTextFn.grantInvoke(analyzeContractFn);

        // Allow analyze-contract to read the externally-managed SecureString Claude key and
        // decrypt it, plus the plain-String model config parameter.
        String ssmBase = "arn:aws:ssm:" + getRegion() + ":" + getAccount() + ":parameter";
        analyzeContractFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("ssm:GetParameter"))
                .resources(List.of(
                        ssmBase + CLAUDE_API_KEY_PARAM,
                        ssmBase + CLAUDE_MODEL_PARAM
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

        Resource analyzeResource = api.getRoot().addResource("analyze");
        analyzeResource.addMethod("POST", new LambdaIntegration(analyzeContractFn));
    }
}
