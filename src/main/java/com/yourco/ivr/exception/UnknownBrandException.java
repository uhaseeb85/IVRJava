package com.yourco.ivr.exception;

public class UnknownBrandException extends RuntimeException {

    public UnknownBrandException(String brandId) {
        super("Unknown brand: " + brandId);
    }
}