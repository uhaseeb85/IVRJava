package com.yourco.ivr.validator;

import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.exception.UnsupportedTokenTypeException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the resolution order of {@link TokenValidatorRegistry#resolve}:
 * brand-specific override → default validator → {@link UnsupportedTokenTypeException}.
 */
class TokenValidatorRegistryTest {

    private static TokenValidator validatorFor(TokenType type) {
        return new TokenValidator() {
            @Override
            public TokenType supportedType() {
                return type;
            }

            @Override
            public ValidationResult validate(TokenValidationContext ctx) {
                return ValidationResult.ok();
            }
        };
    }

    private final TokenValidator defaultPin = validatorFor(TokenType.PIN);
    private final TokenValidator defaultSsn = validatorFor(TokenType.SSN_LAST4);
    private final TokenValidator strictPin = validatorFor(TokenType.PIN);

    private final TokenValidatorRegistry registry = new TokenValidatorRegistry(
        Arrays.asList(defaultPin, defaultSsn),
        Collections.singletonList(new BrandTokenValidatorOverride("acme", strictPin)));

    @Test
    void resolvesDefaultValidatorWhenBrandHasNoOverrides() {
        assertSame(defaultPin, registry.resolve("other-brand", TokenType.PIN));
    }

    @Test
    void brandOverrideTakesPrecedenceOverDefault() {
        assertSame(strictPin, registry.resolve("acme", TokenType.PIN));
    }

    @Test
    void fallsBackToDefaultWhenBrandOverridesLackTheType() {
        assertSame(defaultSsn, registry.resolve("acme", TokenType.SSN_LAST4));
    }

    @Test
    void throwsWhenNoValidatorIsRegisteredForType() {
        assertThrows(UnsupportedTokenTypeException.class,
            () -> registry.resolve("acme", TokenType.VOICE_PRINT));
    }
}
