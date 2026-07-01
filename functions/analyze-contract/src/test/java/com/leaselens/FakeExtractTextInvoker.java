package com.leaselens;

import com.leaselens.service.ExtractTextInvoker;
import com.leaselens.service.ExtractTextResult;

import java.util.Map;

/**
 * Test-only fake for {@link ExtractTextInvoker}: returns a pre-configured text-layer result
 * for a known objectKey, avoiding any real Lambda-to-Lambda invocation.
 */
public class FakeExtractTextInvoker implements ExtractTextInvoker {

    private final Map<String, String> textByObjectKey;
    private final boolean hasTextLayer;

    public FakeExtractTextInvoker(Map<String, String> textByObjectKey) {
        this(textByObjectKey, true);
    }

    public FakeExtractTextInvoker(Map<String, String> textByObjectKey, boolean hasTextLayer) {
        this.textByObjectKey = textByObjectKey;
        this.hasTextLayer = hasTextLayer;
    }

    @Override
    public ExtractTextResult extractText(String objectKey) {
        String text = textByObjectKey.getOrDefault(objectKey, "");
        ExtractTextResult result = new ExtractTextResult();
        result.setText(text);
        result.setPageCount(1);
        result.setHasTextLayer(hasTextLayer);
        result.setSource(hasTextLayer ? "text-layer" : "none");
        return result;
    }
}
