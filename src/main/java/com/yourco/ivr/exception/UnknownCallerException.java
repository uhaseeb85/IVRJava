package com.yourco.ivr.exception;

public class UnknownCallerException extends RuntimeException {
    public UnknownCallerException(String callerId) {
        super("No parties found for caller: " + callerId);
    }
}
