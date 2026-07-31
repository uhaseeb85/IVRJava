package com.yourco.ivr.validator.impl;

import com.yourco.ivr.domain.TokenType;
import org.springframework.stereotype.Component;

/** Format validator for {@link com.yourco.ivr.domain.TokenType#PIN}: requires at least 4 characters. */
@Component
public class PinValidator extends AbstractTokenValidator {

    @Override
    public TokenType supportedType() { return TokenType.PIN; }

    @Override
    protected boolean matches(String tokenValue) {
        return tokenValue.length() >= 4;
    }
}
