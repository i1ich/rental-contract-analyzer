package com.leaselens.service;

/**
 * Kicks off the background analysis for an accepted job.
 *
 * <p>An interface rather than a direct SDK call so the API handler stays testable offline, the
 * same way {@link ExtractTextInvoker} does for the worker side.
 */
public interface AnalysisWorkerInvoker {

    /**
     * Starts the analysis and returns without waiting for it. Implementations must not block on
     * the work completing — the whole point is that the caller can answer the HTTP request in
     * milliseconds.
     */
    void startAnalysis(String jobId, String objectKey);
}
