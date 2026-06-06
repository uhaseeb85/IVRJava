package com.yourco.ivr.exception;

/**
 * Thrown when a session ID is not found in the repository, either because it never existed
 * or because it was deleted after its TTL expired.
 * Mapped to HTTP 404 by {@link com.yourco.ivr.api.IvrExceptionHandler}.
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String sessionId) {
        super("Session not found: " + sessionId);
    }
}