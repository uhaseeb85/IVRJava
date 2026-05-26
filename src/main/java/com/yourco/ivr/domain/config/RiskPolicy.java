package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.TokenType;
import lombok.Data;

import java.util.List;

/**
 * Per-risk-level policy declared inside a brand config.
 *
 * <pre>
 * "riskPolicies": {
 *   "HIGH":     { "minimumTargetLevel": "ELEVATED", "blockedTokens": ["PIN"] },
 *   "CRITICAL": { "reject": true }
 * }
 * </pre>
 *
 * All fields are optional — omit a field to apply no restriction for that dimension.
 */
@Data
public class RiskPolicy {

    /**
     * When {@code true} the session is refused immediately with HTTP 403.
     * Takes precedence over all other fields.
     */
    private boolean reject;

    /**
     * Forces the session's target level to at least this value, regardless of
     * what the caller requested. Has no effect if the caller already requested
     * a higher level.
     */
    private AuthLevel minimumTargetLevel;

    /**
     * Token types that are blocked for this risk level, merged on top of any
     * customer-preference blocks already in place. The engine will skip these
     * and fall back to backup tokens or the next path.
     */
    private List<TokenType> blockedTokens;
}
