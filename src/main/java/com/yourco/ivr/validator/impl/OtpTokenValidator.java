package com.yourco.ivr.validator.impl;

import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.validator.TokenValidationContext;
import com.yourco.ivr.validator.TokenValidator;
import com.yourco.ivr.validator.ValidationErrorCode;
import com.yourco.ivr.validator.ValidationResult;
import org.springframework.stereotype.Component;

@Component
public class OtpTokenValidator implements TokenValidator {

    @Override
    public TokenType supportedType() { return TokenType.OTP; }

    @Override
    public ValidationResult validate(TokenValidationContext ctx) {
        if (ctx.getTokenValue() != null && ctx.getTokenValue().length() == 6) {
            return ValidationResult.ok();
        }
        return ValidationResult.fail(ValidationErrorCode.INVALID);
    }
}