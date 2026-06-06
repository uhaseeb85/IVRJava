package com.yourco.ivr.exception;

/**
 * Thrown when a brand ID is not found in {@link com.yourco.ivr.registry.BrandRulesRegistry}.
 * This means no JSON config file for the brand has been loaded.
 * Mapped to HTTP 400 by {@link com.yourco.ivr.api.IvrExceptionHandler}.
 */
public class UnknownBrandException extends RuntimeException {

    public UnknownBrandException(String brandId) {
        super("Unknown brand: " + brandId);
    }
}