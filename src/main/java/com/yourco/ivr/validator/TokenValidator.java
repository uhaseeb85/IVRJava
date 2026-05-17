package com.yourco.ivr.validator;

import com.yourco.ivr.domain.TokenType;

public interface TokenValidator {
    TokenType supportedType();
    ValidationResult validate(TokenValidationContext ctx);
}