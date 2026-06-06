package com.yourco.ivr.exception;

/**
 * Thrown by {@link com.yourco.ivr.repository.SqliteSessionRepository} when an optimistic-lock
 * conflict is detected — the session's {@code version} in the database no longer matches the
 * in-memory version, meaning another request modified the session between the last read and
 * this write.
 * Mapped to HTTP 409 by {@link com.yourco.ivr.api.IvrExceptionHandler}.
 *
 * <p>Callers should retry the operation by re-reading the session and re-applying the change.
 */
public class SessionConflictException extends RuntimeException {
    public SessionConflictException(String sessionId) {
        super("Session conflict — concurrent modification detected: " + sessionId);
    }
}
