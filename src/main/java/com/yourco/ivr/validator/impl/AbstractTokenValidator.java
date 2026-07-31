package com.yourco.ivr.validator.impl;

import com.yourco.ivr.validator.TokenValidationContext;
import com.yourco.ivr.validator.TokenValidator;
import com.yourco.ivr.validator.ValidationErrorCode;
import com.yourco.ivr.validator.ValidationResult;

/**
 * Base class for format validators that decide purely from the token value string.
 *
 * <p>Subclasses implement {@link #matches(String)}; a {@code null} value always fails.
 * The concrete validators remain Spring {@code @Component}s discovered by
 * {@link com.yourco.ivr.validator.TokenValidatorRegistry}.
 */
public abstract class AbstractTokenValidator implements TokenValidator {

    @Override
    public final ValidationResult validate(TokenValidationContext ctx) {
        String value = ctx.getTokenValue();
        if (value == null || !matches(value)) {
            return ValidationResult.fail(ValidationErrorCode.INVALID);
        }
        return ValidationResult.ok();
    }

    /** Returns {@code true} when the non-null token value is structurally valid. */
    protected abstract boolean matches(String tokenValue);
}
