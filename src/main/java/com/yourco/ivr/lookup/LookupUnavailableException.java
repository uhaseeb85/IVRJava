package com.yourco.ivr.lookup;

/**
 * Thrown by {@link LookupExecutor} when a backend verification call cannot produce a result:
 * it timed out, the backend threw, or the service's circuit breaker is open.
 *
 * <p>This is an internal signal — it is caught inside
 * {@link com.yourco.ivr.engine.validation.ExternalValidator}, which applies the binding's
 * {@code failClosed} policy. It is never propagated to the global exception handler.
 */
public class LookupUnavailableException extends RuntimeException {

    public LookupUnavailableException(String message) {
        super(message);
    }

    public LookupUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
