package com.leaselens;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.s3.*;
import software.constructs.Construct;

import java.util.List;

/**
 * Persistent layer for LeaseLens: contract uploads (S3) and analysis results (DynamoDB).
 *
 * <p>Contract with downstream Lambda modules — do not rename without checking callers:
 * <ul>
 *     <li>The extract-text Lambda reads the {@code BUCKET_NAME} env var pointing at
 *     {@link #getContractUploadsBucket()}.</li>
 *     <li>The analyze-contract Lambda reads the {@code TABLE_NAME} env var pointing at
 *     {@link #getAnalysesTable()}, whose partition key is {@code contentHash}.</li>
 * </ul>
 */
public class LeaseLensStorageStack extends Stack {

    private final Bucket contractUploadsBucket;
    private final Table analysesTable;

    public LeaseLensStorageStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // S3 bucket for contract PDF uploads — 24h lifecycle, since contracts contain PII
        // and must not linger. Browser uploads directly via a presigned URL (PUT).
        contractUploadsBucket = Bucket.Builder.create(this, "ContractUploadsBucket")
                .versioned(false)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .encryption(BucketEncryption.S3_MANAGED)
                .lifecycleRules(List.of(
                        LifecycleRule.builder()
                                .expiration(Duration.days(1))
                                .build()
                ))
                .cors(List.of(
                        CorsRule.builder()
                                .allowedMethods(List.of(HttpMethods.PUT, HttpMethods.POST, HttpMethods.GET))
                                .allowedOrigins(List.of("*"))
                                .allowedHeaders(List.of("*"))
                                .build()
                ))
                .build();

        // DynamoDB table for analysis results, keyed by content hash (not the raw document)
        // per the privacy design. Items expire via the `ttl` attribute.
        analysesTable = Table.Builder.create(this, "AnalysesTable")
                .tableName("leaselens-analyses")
                .partitionKey(Attribute.builder()
                        .name("contentHash")
                        .type(AttributeType.STRING)
                        .build())
                .timeToLiveAttribute("ttl")
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build();
    }

    public Bucket getContractUploadsBucket() {
        return contractUploadsBucket;
    }

    public Table getAnalysesTable() {
        return analysesTable;
    }
}
