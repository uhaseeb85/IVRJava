package com.yourco.ivr.validator.impl;

import com.yourco.ivr.domain.TokenType;
import org.springframework.stereotype.Component;

/** Format validator for {@link com.yourco.ivr.domain.TokenType#SSN_LAST4}: requires exactly 4 characters. */
@Component
public class SsnLast4Validator extends AbstractTokenValidator {

    @Override
    public TokenType supportedType() { return TokenType.SSN_LAST4; }

    @Override
    protected boolean matches(String tokenValue) {
        return tokenValue.length() == 4;
    }
}
