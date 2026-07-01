package com.leaselens.service;

/**
 * Result of invoking the {@code extract-text} Lambda, mirroring its documented response
 * contract: {"text": "...", "pageCount": N, "hasTextLayer": bool, "source": "text-layer"|"none"}.
 */
public class ExtractTextResult {

    private String text;
    private int pageCount;
    private boolean hasTextLayer;
    private String source;

    public ExtractTextResult() {
    }

    public ExtractTextResult(String text, int pageCount, boolean hasTextLayer, String source) {
        this.text = text;
        this.pageCount = pageCount;
        this.hasTextLayer = hasTextLayer;
        this.source = source;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public int getPageCount() {
        return pageCount;
    }

    public void setPageCount(int pageCount) {
        this.pageCount = pageCount;
    }

    public boolean isHasTextLayer() {
        return hasTextLayer;
    }

    public void setHasTextLayer(boolean hasTextLayer) {
        this.hasTextLayer = hasTextLayer;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
