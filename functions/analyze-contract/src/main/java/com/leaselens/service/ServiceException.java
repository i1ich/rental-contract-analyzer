package com.leaselens.service;

/** Thrown when an external call (Lambda invoke, Claude API, etc.) fails. Maps to HTTP 502. */
public class ServiceException extends RuntimeException {

    public ServiceException(String message) {
        super(message);
    }

    public ServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
