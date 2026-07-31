package com.yourco.ivr.validator.impl;

import com.yourco.ivr.domain.TokenType;
import org.springframework.stereotype.Component;

/** Format validator for {@link com.yourco.ivr.domain.TokenType#OTP}: requires exactly 6 characters. */
@Component
public class OtpTokenValidator extends AbstractTokenValidator {

    @Override
    public TokenType supportedType() { return TokenType.OTP; }

    @Override
    protected boolean matches(String tokenValue) {
        return tokenValue.length() == 6;
    }
}
