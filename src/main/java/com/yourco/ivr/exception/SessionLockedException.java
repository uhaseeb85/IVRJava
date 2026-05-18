package com.yourco.ivr.exception;

public class SessionLockedException extends RuntimeException {

    public SessionLockedException(String sessionId) {
        super("Session is locked: " + sessionId);
    }
}
