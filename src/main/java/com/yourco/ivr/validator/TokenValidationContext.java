package com.yourco.ivr.validator;

import java.util.Map;

import com.yourco.ivr.domain.TokenType;

/**
 * Immutable context passed to {@link TokenValidator#validate(TokenValidationContext)}.
 *
 * <p>Carries everything a format validator might need: the token being validated, other
 * tokens already collected this session (for cross-field checks), the caller's ANI, and
 * the brand ID.
 *
 * <p><strong>Security:</strong> {@code tokenValue} is sensitive. Validators must not log it.
 */
public class TokenValidationContext {

    private final TokenType tokenType;
    /** Sensitive — never log this value. */
    private final String tokenValue;
    private final String callerId;
    private final Map<TokenType, String> sessionTokens;
    private final String brandId;

    public TokenValidationContext(TokenType tokenType, String tokenValue,
                                    String callerId, Map<TokenType, String> sessionTokens,
                                    String brandId) {
        this.tokenType = tokenType;
        this.tokenValue = tokenValue;
        this.callerId = callerId;
        this.sessionTokens = sessionTokens;
        this.brandId = brandId;
    }

    public TokenType getTokenType() { return tokenType; }
    public String getTokenValue() { return tokenValue; }
    public String getCallerId() { return callerId; }
    public Map<TokenType, String> getSessionTokens() { return sessionTokens; }
    public String getBrandId() { return brandId; }
}