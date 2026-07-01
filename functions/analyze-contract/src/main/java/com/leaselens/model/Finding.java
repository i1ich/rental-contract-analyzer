package com.leaselens.model;

/**
 * A single finding (risky/notable clause) surfaced by the contract analysis.
 * {@code severity} uses the string values "red" / "yellow" / "green"; the frontend
 * maps these to visual indicators (red/yellow/green dot) later.
 */
public class Finding {

    private String severity;
    private String clauseQuote;
    private String location;
    private String plainExplanation;
    private String whyItMatters;

    public Finding() {
    }

    public Finding(String severity, String clauseQuote, String location,
                    String plainExplanation, String whyItMatters) {
        this.severity = severity;
        this.clauseQuote = clauseQuote;
        this.location = location;
        this.plainExplanation = plainExplanation;
        this.whyItMatters = whyItMatters;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getClauseQuote() {
        return clauseQuote;
    }

    public void setClauseQuote(String clauseQuote) {
        this.clauseQuote = clauseQuote;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getPlainExplanation() {
        return plainExplanation;
    }

    public void setPlainExplanation(String plainExplanation) {
        this.plainExplanation = plainExplanation;
    }

    public String getWhyItMatters() {
        return whyItMatters;
    }

    public void setWhyItMatters(String whyItMatters) {
        this.whyItMatters = whyItMatters;
    }
}
