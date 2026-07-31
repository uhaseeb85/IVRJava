package com.yourco.ivr.validator.impl;

import com.yourco.ivr.domain.TokenType;
import org.springframework.stereotype.Component;

/** Format validator for {@link com.yourco.ivr.domain.TokenType#ACCOUNT_NUMBER}: accepts any non-blank value. */
@Component
public class AccountNumberValidator extends AbstractTokenValidator {

    @Override
    public TokenType supportedType() { return TokenType.ACCOUNT_NUMBER; }

    @Override
    protected boolean matches(String tokenValue) {
        return !tokenValue.trim().isEmpty();
    }
}
