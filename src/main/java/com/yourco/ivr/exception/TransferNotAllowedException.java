package com.yourco.ivr.exception;

public class TransferNotAllowedException extends RuntimeException {

    public TransferNotAllowedException(String message) {
        super(message);
    }
}
