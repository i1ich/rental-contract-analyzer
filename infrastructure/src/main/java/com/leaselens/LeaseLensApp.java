package com.leaselens;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

// Entry point for the CDK app. Stacks (Storage / Api / Frontend) are added
// as their respective build tasks land — see docs/DEPLOYMENT.md.
public class LeaseLensApp {

    public static void main(final String[] args) {
        App app = new App();

        Environment env = Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region("sa-east-1")
                .build();

        StackProps props = StackProps.builder()
                .env(env)
                .build();

        LeaseLensStorageStack storageStack = new LeaseLensStorageStack(app, "LeaseLensStorageStack", props);
        new LeaseLensApiStack(app, "LeaseLensApiStack", props, storageStack);
        new LeaseLensFrontendStack(app, "LeaseLensFrontendStack", props);

        app.synth();
    }
}
