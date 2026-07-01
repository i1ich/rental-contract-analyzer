package com.leaselens.service;

import com.leaselens.model.AnalysisResult;

/** Analyzes rental contract text and returns a structured, Spanish-language result. */
public interface ContractAnalysisService {

    /**
     * @param contractText normalized text extracted from the uploaded PDF
     * @return structured analysis (summary + findings)
     * @throws ServiceException if the underlying analysis call fails or returns unparseable output
     */
    AnalysisResult analyze(String contractText);
}
