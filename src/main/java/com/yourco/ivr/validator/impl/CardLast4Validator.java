package com.yourco.ivr.validator.impl;

import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.validator.TokenValidationContext;
import com.yourco.ivr.validator.TokenValidator;
import com.yourco.ivr.validator.ValidationErrorCode;
import com.yourco.ivr.validator.ValidationResult;
import org.springframework.stereotype.Component;

@Component
public class CardLast4Validator implements TokenValidator {

    @Override
    public TokenType supportedType() { return TokenType.CARD_LAST4; }

    @Override
    public ValidationResult validate(TokenValidationContext ctx) {
        if (ctx.getTokenValue() != null && ctx.getTokenValue().length() == 4) {
            return ValidationResult.ok();
        }
        return ValidationResult.fail(ValidationErrorCode.INVALID);
    }
}