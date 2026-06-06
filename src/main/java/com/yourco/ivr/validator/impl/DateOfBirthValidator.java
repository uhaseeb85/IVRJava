package com.yourco.ivr.validator.impl;

import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.validator.TokenValidationContext;
import com.yourco.ivr.validator.TokenValidator;
import com.yourco.ivr.validator.ValidationErrorCode;
import com.yourco.ivr.validator.ValidationResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Format validator for {@link com.yourco.ivr.domain.TokenType#DATE_OF_BIRTH}.
 * Accepts values parseable by {@link java.time.format.DateTimeFormatter#ISO_LOCAL_DATE} (YYYY-MM-DD).
 */
@Component
public class DateOfBirthValidator implements TokenValidator {

    @Override
    public TokenType supportedType() { return TokenType.DATE_OF_BIRTH; }

    @Override
    public ValidationResult validate(TokenValidationContext ctx) {
        try {
            LocalDate.parse(ctx.getTokenValue(), DateTimeFormatter.ISO_LOCAL_DATE);
            return ValidationResult.ok();
        } catch (DateTimeParseException e) {
            return ValidationResult.fail(ValidationErrorCode.INVALID);
        }
    }
}