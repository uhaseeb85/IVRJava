package com.yourco.ivr.exception;

import java.time.Instant;

/**
 * Thrown when a token submission is attempted on a session that is in the
 * {@link com.yourco.ivr.domain.SessionStatus#REDIRECT_TO_AGENT} state and the redirect window has not yet elapsed.
 * Mapped to HTTP 423 by {@link com.yourco.ivr.api.IvrExceptionHandler}.
 *
 * <p>The engine auto-resets expired redirect windows on the next token submission — callers do not
 * need to take any explicit action other than retrying after {@code lockedUntil}.
 */
public class SessionLockedException extends RuntimeException {

    /** Used when no expiry time is available (e.g. redirect with no timestamp set). */
    public SessionLockedException(String sessionId) {
        super("Session is redirecting to agent: " + sessionId);
    }

    /** Preferred constructor — includes the exact expiry time for client retry guidance. */
    public SessionLockedException(String sessionId, Instant lockedUntil) {
        super("Session is redirecting to agent until " + lockedUntil + ": " + sessionId);
    }
}
