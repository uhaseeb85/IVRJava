package com.yourco.ivr.validator;

import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.validator.impl.AbstractTokenValidator;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shared {@link AbstractTokenValidator} contract: {@code null} always fails without
 * invoking {@code matches}, and {@code matches} decides non-null values.
 */
class AbstractTokenValidatorTest {

    private final AbstractTokenValidator validator = new AbstractTokenValidator() {
        @Override
        public TokenType supportedType() {
            return TokenType.PIN;
        }

        @Override
        protected boolean matches(String tokenValue) {
            return tokenValue.length() == 4;
        }
    };

    private ValidationResult validate(String value) {
        return validator.validate(new TokenValidationContext(
            TokenType.PIN, value, "5551234567", Collections.emptyMap(), "BRAND_A"));
    }

    @Test
    void nullValueFailsWithoutMatching() {
        assertFalse(validate(null).isValid());
    }

    @Test
    void matchingValuePasses() {
        assertTrue(validate("1234").isValid());
    }

    @Test
    void nonMatchingValueFailsWithInvalidCode() {
        ValidationResult result = validate("12345");
        assertFalse(result.isValid());
        assertTrue(result.getErrorCode() == ValidationErrorCode.INVALID);
    }
}
