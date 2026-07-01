package com.leaselens.model;

/** API Gateway request body: {"objectKey": "<s3 key of the already-uploaded PDF>"} */
public class AnalyzeContractRequest {

    private String objectKey;

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }
}
