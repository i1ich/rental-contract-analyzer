package com.leaselens.model;

/**
 * API Gateway request body:
 * {"objectKey": "<s3 key of the already-uploaded PDF>", "licenseKey": "<optional, see T18>"}.
 * The license key travels in the body, never the URL, so it stays out of access logs and referrers.
 */
public class AnalyzeContractRequest {

    private String objectKey;
    private String licenseKey;

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getLicenseKey() {
        return licenseKey;
    }

    public void setLicenseKey(String licenseKey) {
        this.licenseKey = licenseKey;
    }
}
