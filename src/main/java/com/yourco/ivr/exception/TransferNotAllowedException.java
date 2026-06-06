package com.yourco.ivr.exception;

/**
 * Thrown when a call-transfer request is rejected because the source system ID is not
 * configured in {@link com.yourco.ivr.registry.TransferPoliciesRegistry}, or its policy
 * is disabled.
 * Mapped to HTTP 403 by {@link com.yourco.ivr.api.IvrExceptionHandler}.
 */
public class TransferNotAllowedException extends RuntimeException {

    public TransferNotAllowedException(String message) {
        super(message);
    }
}
