package com.yourco.ivr.exception;

/**
 * Thrown by {@link com.yourco.ivr.service.BrandService} when a brand config file cannot be
 * read, written, or deleted due to an I/O error. Distinct from validation errors
 * ({@link IllegalArgumentException}) which indicate a malformed config.
 * Mapped to HTTP 500 by {@link com.yourco.ivr.api.IvrExceptionHandler}.
 */
public class BrandConfigException extends RuntimeException {
    public BrandConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
