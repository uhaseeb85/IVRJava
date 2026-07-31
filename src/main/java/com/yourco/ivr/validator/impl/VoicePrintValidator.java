package com.yourco.ivr.validator.impl;

import com.yourco.ivr.domain.TokenType;
import org.springframework.stereotype.Component;

/** Format validator for {@link com.yourco.ivr.domain.TokenType#VOICE_PRINT}: accepts any non-blank value. */
@Component
public class VoicePrintValidator extends AbstractTokenValidator {

    @Override
    public TokenType supportedType() { return TokenType.VOICE_PRINT; }

    @Override
    protected boolean matches(String tokenValue) {
        return !tokenValue.trim().isEmpty();
    }
}
