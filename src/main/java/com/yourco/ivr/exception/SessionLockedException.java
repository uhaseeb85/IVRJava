package com.yourco.ivr.exception;

import java.time.Instant;

/**
 * Thrown when a token submission is attempted on a session that is in the
 * {@link com.yourco.ivr.domain.SessionStatus#LOCKED} state and the lock has not yet expired.
 * Mapped to HTTP 423 (Locked) by {@link com.yourco.ivr.api.IvrExceptionHandler}.
 *
 * <p>The engine auto-unlocks expired locks on the next token submission — callers do not
 * need to take any explicit action other than retrying after {@code lockedUntil}.
 */
public class SessionLockedException extends RuntimeException {

    /** Used when no expiry time is available (e.g. lock with no timestamp set). */
    public SessionLockedException(String sessionId) {
        super("Session is locked: " + sessionId);
    }

    /** Preferred constructor — includes the exact expiry time for client retry guidance. */
    public SessionLockedException(String sessionId, Instant lockedUntil) {
        super("Session is locked until " + lockedUntil + ": " + sessionId);
    }
}
