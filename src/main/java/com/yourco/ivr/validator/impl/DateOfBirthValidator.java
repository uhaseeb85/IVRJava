package com.yourco.ivr.validator.impl;

import com.yourco.ivr.domain.TokenType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Format validator for {@link com.yourco.ivr.domain.TokenType#DATE_OF_BIRTH}.
 * Accepts values parseable by {@link java.time.format.DateTimeFormatter#ISO_LOCAL_DATE} (YYYY-MM-DD).
 */
@Component
public class DateOfBirthValidator extends AbstractTokenValidator {

    @Override
    public TokenType supportedType() { return TokenType.DATE_OF_BIRTH; }

    @Override
    protected boolean matches(String tokenValue) {
        try {
            LocalDate.parse(tokenValue, DateTimeFormatter.ISO_LOCAL_DATE);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
