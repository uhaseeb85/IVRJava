package com.yourco.ivr.validator;

import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.exception.UnsupportedTokenTypeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

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

    public TokenValidator resolve(String brandId, TokenType type) {
        return Optional.ofNullable(brandOverrides.get(brandId))
            .map(m -> m.get(type))
            .orElseGet(() -> Optional.ofNullable(defaults.get(type))
                .orElseThrow(() -> new UnsupportedTokenTypeException(type)));
    }
}