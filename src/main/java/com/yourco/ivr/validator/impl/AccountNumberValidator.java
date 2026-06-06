package com.yourco.ivr.validator.impl;

import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.validator.TokenValidationContext;
import com.yourco.ivr.validator.TokenValidator;
import com.yourco.ivr.validator.ValidationResult;
import org.springframework.stereotype.Component;

/** Format validator for {@link com.yourco.ivr.domain.TokenType#ACCOUNT_NUMBER}: accepts any non-blank value. */
@Component
public class AccountNumberValidator implements TokenValidator {

    @Override
    public TokenType supportedType() {
        return TokenType.ACCOUNT_NUMBER;
    }

    @Override
    public ValidationResult validate(TokenValidationContext ctx) {
        // Stub: accept any non-blank token value
        if (ctx.getTokenValue() != null && !ctx.getTokenValue().trim().isEmpty()) {
            return ValidationResult.ok();
        }
        return ValidationResult.fail(com.yourco.ivr.validator.ValidationErrorCode.INVALID);
    }
}