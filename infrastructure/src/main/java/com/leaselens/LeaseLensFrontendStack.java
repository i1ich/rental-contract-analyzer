package com.leaselens;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.cloudfront.Distribution;
import software.amazon.awscdk.services.cloudfront.ErrorResponse;
import software.amazon.awscdk.services.cloudfront.IOrigin;
import software.amazon.awscdk.services.cloudfront.OriginAccessIdentity;
import software.amazon.awscdk.services.cloudfront.ViewerProtocolPolicy;
import software.amazon.awscdk.services.cloudfront.origins.S3Origin;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.constructs.Construct;

import java.util.List;

/**
 * T11 — hosts the built frontend PWA (`frontend/dist`, built via {@code npm run build}) behind
 * CloudFront so it has a public URL instead of only a local Vite dev server.
 *
 * <p>The origin bucket is private (no static website hosting, no public access) — CloudFront
 * reaches it via an Origin Access Identity, matching the "blocked public access" posture used
 * for the uploads bucket in {@link LeaseLensStorageStack}. 403/404 responses (e.g. a client-side
 * route with no matching S3 key) are rewritten to {@code index.html} with a 200, since this is a
 * single-page app and routing is handled client-side.
 *
 * <p>Bucket content is disposable build output, not user data — {@code removalPolicy(DESTROY)}
 * and {@code autoDeleteObjects(true)} so a stack teardown doesn't leave an orphaned bucket
 * (unlike the uploads/analyses stores in {@link LeaseLensStorageStack}, which default to RETAIN).
 *
 * <p><b>Deliberately does NOT use CDK's {@code BucketDeployment} construct.</b> On this project's
 * pinned CDK version (2.130.0, ~March 2024), that construct's bundled AWS-CLI Lambda layer runs
 * Python 3.9 but AWS has since updated the layer's underlying awscli/botocore package to one that
 * uses PEP 604 union syntax (`bytes | str`), which is a Python 3.10+ feature — every deployment
 * attempt fails with {@code TypeError: unsupported operand type(s) for |: 'type' and 'type'}
 * inside the custom resource, confirmed live via its CloudWatch logs. This is an external
 * version-skew bug in the old CDK release, not something fixable in this stack's own code, and
 * upgrading the pinned CDK version is a larger, riskier change than this task warrants. Instead,
 * asset upload + cache invalidation is a manual post-deploy step — see {@code docs/DEPLOYMENT.md}.
 */
public class LeaseLensFrontendStack extends Stack {

    public LeaseLensFrontendStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        Bucket siteBucket = Bucket.Builder.create(this, "FrontendSiteBucket")
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build();

        OriginAccessIdentity oai = OriginAccessIdentity.Builder.create(this, "FrontendOai").build();
        siteBucket.grantRead(oai);

        IOrigin origin = S3Origin.Builder.create(siteBucket).originAccessIdentity(oai).build();

        Distribution distribution = Distribution.Builder.create(this, "FrontendDistribution")
                .defaultRootObject("index.html")
                .defaultBehavior(software.amazon.awscdk.services.cloudfront.BehaviorOptions.builder()
                        .origin(origin)
                        .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                        .build())
                // SPA fallback: any unknown path (client-side route) serves index.html instead
                // of CloudFront's default XML error page.
                .errorResponses(List.of(
                        ErrorResponse.builder()
                                .httpStatus(403)
                                .responseHttpStatus(200)
                                .responsePagePath("/index.html")
                                .build(),
                        ErrorResponse.builder()
                                .httpStatus(404)
                                .responseHttpStatus(200)
                                .responsePagePath("/index.html")
                                .build()
                ))
                .build();

        CfnOutput.Builder.create(this, "FrontendBucketName")
                .value(siteBucket.getBucketName())
                .build();
        CfnOutput.Builder.create(this, "FrontendDistributionId")
                .value(distribution.getDistributionId())
                .build();
        CfnOutput.Builder.create(this, "FrontendUrl")
                .value("https://" + distribution.getDistributionDomainName())
                .build();
    }
}
