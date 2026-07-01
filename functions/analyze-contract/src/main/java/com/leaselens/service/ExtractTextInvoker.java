package com.leaselens.service;

/**
 * Seam over the {@code extract-text} Lambda invocation, so the handler can be unit-tested
 * with a fake implementation instead of a real Lambda-to-Lambda call.
 */
public interface ExtractTextInvoker {

    /**
     * Invokes the extract-text Lambda synchronously for the given S3 object key.
     *
     * @param objectKey S3 key of the already-uploaded PDF
     * @return the extracted-text result
     * @throws ServiceException if the invocation fails or the response cannot be parsed
     */
    ExtractTextResult extractText(String objectKey);
}
