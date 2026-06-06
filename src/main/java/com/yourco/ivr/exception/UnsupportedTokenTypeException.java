package com.yourco.ivr.exception;

import com.yourco.ivr.domain.TokenType;

/**
 * Thrown by {@link com.yourco.ivr.validator.TokenValidatorRegistry#resolve} when no
 * {@link com.yourco.ivr.validator.TokenValidator} is registered for the submitted token type
 * (neither a brand-specific override nor a default validator).
 * Mapped to HTTP 400 by {@link com.yourco.ivr.api.IvrExceptionHandler}.
 */
public class UnsupportedTokenTypeException extends RuntimeException {

    public UnsupportedTokenTypeException(TokenType tokenType) {
        super("Unsupported token type: " + tokenType);
    }
}