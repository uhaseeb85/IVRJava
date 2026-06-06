package com.yourco.ivr.validator;

import com.yourco.ivr.domain.TokenType;

/**
 * Format-level validator for a single {@link TokenType}.
 *
 * <p>Each implementation checks that a submitted token value is structurally valid
 * (e.g. correct length, parseable date) <em>without</em> calling any external system.
 * This is the first gate in a two-stage validation pipeline; the second gate is an optional
 * backend call via {@link com.yourco.ivr.lookup.TokenLookupService}.
 *
 * <p>Implementations are discovered by {@link TokenValidatorRegistry} via Spring auto-wiring.
 * To add a new token type: create a {@code @Component} that implements this interface and
 * returns the new type from {@link #supportedType()}. No further wiring is needed.
 *
 * <p><strong>Security:</strong> implementations must never log the raw {@code tokenValue}
 * from the context — only validation outcome (PASS/FAIL) and token type are safe to log.
 *
 * @see TokenValidatorRegistry
 * @see com.yourco.ivr.validator.impl
 */
public interface TokenValidator {
    /** The single token type this validator handles. Must be unique across all registered validators. */
    TokenType supportedType();

    /**
     * Validates the format of the submitted token value.
     *
     * @param ctx context containing the token value, type, brand, and other session tokens
     * @return {@link ValidationResult#ok()} if the format is acceptable;
     *         {@link ValidationResult#fail(ValidationErrorCode)} with an appropriate code otherwise
     */
    ValidationResult validate(TokenValidationContext ctx);
}