package com.leaselens.model;

import java.util.List;

/**
 * Structured output of a contract analysis: a short Spanish summary plus a list of findings.
 * This is also the shape persisted (as JSON) in the DynamoDB cache and returned by the API,
 * with {@code cachedAt} populated only when served from cache.
 */
public class AnalysisResult {

    private String summary;
    private List<Finding> findings;
    private String cachedAt;

    public AnalysisResult() {
    }

    public AnalysisResult(String summary, List<Finding> findings) {
        this.summary = summary;
        this.findings = findings;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<Finding> getFindings() {
        return findings;
    }

    public void setFindings(List<Finding> findings) {
        this.findings = findings;
    }

    public String getCachedAt() {
        return cachedAt;
    }

    public void setCachedAt(String cachedAt) {
        this.cachedAt = cachedAt;
    }
}
