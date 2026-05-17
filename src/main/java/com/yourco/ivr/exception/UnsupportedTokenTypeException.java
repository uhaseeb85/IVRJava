package com.yourco.ivr.exception;

import com.yourco.ivr.domain.TokenType;

public class UnsupportedTokenTypeException extends RuntimeException {

    public UnsupportedTokenTypeException(TokenType tokenType) {
        super("Unsupported token type: " + tokenType);
    }
}