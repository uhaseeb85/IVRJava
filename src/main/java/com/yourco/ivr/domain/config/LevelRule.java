package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.TokenType;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Authentication rules for a single target {@link com.yourco.ivr.domain.AuthLevel}.
 *
 * <p>A level rule defines one or more ordered {@link TokenPath}s. The engine tries path 0
 * first; if the caller exhausts retries on that path, the engine advances to path 1, then
 * path 2, and so on. If all paths are exhausted the session is locked.
 *
 * <p>Example JSON:
 * <pre>
 * {
 *   "paths": [
 *     { "pathIndex": 0, "requiredTokens": ["ACCOUNT_NUMBER", "PIN"] },
 *     { "pathIndex": 1, "requiredTokens": ["ACCOUNT_NUMBER", "SSN_LAST4"] }
 *   ],
 *   "maxRetriesPerToken": 3,
 *   "tokenRetryLimits": { "PIN": 2, "ACCOUNT_NUMBER": 5 },
 *   "lockoutSeconds": 300
 * }
 * </pre>
 */
@Data
public class LevelRule {
    /**
     * Ordered list of token paths. Path index 0 is the primary path; subsequent paths are
     * fallbacks tried in order after the previous path's retry budget is exhausted.
     */
    private List<TokenPath> paths;

    /**
     * Default maximum failed attempts allowed per required-token slot. Applied to any token
     * type not listed in {@link #tokenRetryLimits}.
     */
    private int maxRetriesPerToken;

    /**
     * Per-token retry overrides. When present, the value here takes precedence over
     * {@link #maxRetriesPerToken} for that specific token type. Tokens not listed here
     * fall back to {@code maxRetriesPerToken}.
     */
    private Map<TokenType, Integer> tokenRetryLimits;

    /** Duration in seconds the session stays locked after all retry paths are exhausted. */
    private int lockoutSeconds;

    /** Returns the retry limit for {@code tokenType}, using the per-token override if configured. */
    public int getMaxRetriesFor(TokenType tokenType) {
        if (tokenRetryLimits != null && tokenRetryLimits.containsKey(tokenType)) {
            return tokenRetryLimits.get(tokenType);
        }
        return maxRetriesPerToken;
    }
}