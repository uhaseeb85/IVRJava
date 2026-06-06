package com.yourco.ivr.exception;

/**
 * Thrown by {@link com.yourco.ivr.repository.SqliteSessionRepository} when Jackson fails to
 * serialize or deserialize a session's JSON columns. This indicates an internal data corruption
 * or schema mismatch rather than a client error.
 * Mapped to HTTP 500 by {@link com.yourco.ivr.api.IvrExceptionHandler}.
 */
public class SessionSerializationException extends RuntimeException {

    public SessionSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}