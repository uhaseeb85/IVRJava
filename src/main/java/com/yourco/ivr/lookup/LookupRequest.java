package com.yourco.ivr.lookup;

import com.yourco.ivr.domain.TokenType;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable input to {@link TokenLookupService#verify(LookupRequest)}.
 *
 * <p>{@link #getTokenValue()} is sensitive and must never be logged.
 */
public final class LookupRequest {

    private final TokenType tokenType;
    private final String tokenValue;
    private final String callerId;
    private final String brandId;
    private final Map<TokenType, String> sessionTokens;
    private final Map<String, String> params;

    public LookupRequest(TokenType tokenType, String tokenValue, String callerId,
                         String brandId, Map<TokenType, String> sessionTokens,
                         Map<String, String> params) {
        this.tokenType = tokenType;
        this.tokenValue = tokenValue;
        this.callerId = callerId;
        this.brandId = brandId;
        this.sessionTokens = sessionTokens != null ? sessionTokens : Collections.emptyMap();
        this.params = params != null ? params : Collections.emptyMap();
    }

    /** The token type being verified. */
    public TokenType getTokenType() { return tokenType; }

    /** Raw token value. Sensitive — never log this. */
    public String getTokenValue() { return tokenValue; }

    /** Caller ANI / identifier. */
    public String getCallerId() { return callerId; }

    /** Brand the session belongs to. */
    public String getBrandId() { return brandId; }

    /** Tokens already collected this session (e.g. account number), usable as lookup keys. */
    public Map<TokenType, String> getSessionTokens() { return sessionTokens; }

    /** Per-brand binding params from config (e.g. region, dataset). Never null. */
    public Map<String, String> getParams() { return params; }
}
