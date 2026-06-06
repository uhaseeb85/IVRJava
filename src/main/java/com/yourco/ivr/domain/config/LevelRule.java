package com.yourco.ivr.domain.config;

import lombok.Data;

import java.util.List;

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
     * Maximum number of failed attempts allowed per required-token slot before the engine
     * either switches to the next fallback path (if {@code allowPathSwitch} applies) or
     * locks the session.
     */
    private int maxRetriesPerToken;

    /** Duration in seconds the session stays locked after all retry paths are exhausted. */
    private int lockoutSeconds;
}