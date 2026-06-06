package com.yourco.ivr.exception;

/**
 * Thrown when a brand config references a lookup service id that is not present in the
 * {@link com.yourco.ivr.lookup.LookupServiceRegistry}.
 */
public class UnknownLookupServiceException extends RuntimeException {

    public UnknownLookupServiceException(String serviceId) {
        super("Unknown lookup service: " + serviceId);
    }
}
