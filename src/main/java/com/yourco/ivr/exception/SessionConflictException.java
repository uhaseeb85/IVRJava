package com.yourco.ivr.exception;

public class SessionConflictException extends RuntimeException {
    public SessionConflictException(String sessionId) {
        super("Session conflict — concurrent modification detected: " + sessionId);
    }
}
