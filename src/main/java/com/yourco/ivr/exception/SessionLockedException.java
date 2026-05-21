package com.yourco.ivr.exception;

import java.time.Instant;

public class SessionLockedException extends RuntimeException {

    public SessionLockedException(String sessionId) {
        super("Session is locked: " + sessionId);
    }

    public SessionLockedException(String sessionId, Instant lockedUntil) {
        super("Session is locked until " + lockedUntil + ": " + sessionId);
    }
}
