package com.yourco.ivr.validator;

import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.exception.UnsupportedTokenTypeException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

/**
 * In-memory registry of all {@link TokenValidator} beans, with optional per-brand overrides.
 *
 * <p>At startup Spring auto-wires every {@code @Component} that implements
 * {@link TokenValidator} into the {@code validators} list. The registry indexes them by
 * {@link TokenValidator#supportedType()} to allow O(1) lookup. Adding a validator for a new
 * token type requires only creating a new {@code @Component} — no further wiring is needed.
 *
 * <p>{@link BrandTokenValidatorOverride} beans allow a specific brand to use a different
 * validator implementation for a given token type (e.g. a stricter PIN policy for a
 * high-security brand). Brand overrides take precedence over the default validators.
 */
@Component
public class TokenValidatorRegistry {

    private final Map<TokenType, TokenValidator> defaults;
    private final Map<String, Map<TokenType, TokenValidator>> brandOverrides;

    public TokenValidatorRegistry(List<TokenValidator> validators,
                                  List<BrandTokenValidatorOverride> overrides) {
        this.defaults = validators.stream()
            .collect(toMap(TokenValidator::supportedType, v -> v));
        this.brandOverrides = overrides.stream()
            .collect(groupingBy(BrandTokenValidatorOverride::getBrandId,
                toMap(o -> o.getValidator().supportedType(),
                      BrandTokenValidatorOverride::getValidator)));
    }

    /**
     * Returns the {@link TokenValidator} to use for the given brand and token type.
     *
     * <p>Lookup order: brand-specific override → default validator.
     *
     * @throws com.yourco.ivr.exception.UnsupportedTokenTypeException if no validator is
     *         registered for {@code type} (neither override nor default)
     */
    public TokenValidator resolve(String brandId, TokenType type) {
        Map<TokenType, TokenValidator> overrides = brandOverrides.get(brandId);
        TokenValidator validator = overrides != null ? overrides.get(type) : null;
        if (validator == null) {
            validator = defaults.get(type);
        }
        if (validator == null) {
            throw new UnsupportedTokenTypeException(type);
        }
        return validator;
    }
}