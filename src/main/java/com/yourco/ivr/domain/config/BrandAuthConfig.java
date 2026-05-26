package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.RiskLevel;
import lombok.Data;

import java.util.Map;

@Data
public class BrandAuthConfig {
    private String brandId;
    private Map<AuthLevel, LevelRule> levelRules;
    private DisambiguationConfig disambiguation;

    /**
     * Optional per-risk-level policies. Keyed by {@link RiskLevel}.
     * When a caller's risk matches a key, the corresponding {@link RiskPolicy}
     * is applied before any authentication steps begin.
     *
     * <pre>
     * "riskPolicies": {
     *   "HIGH":     { "minimumTargetLevel": "ELEVATED", "blockedTokens": ["PIN"] },
     *   "CRITICAL": { "reject": true }
     * }
     * </pre>
     */
    private Map<RiskLevel, RiskPolicy> riskPolicies;

    public DisambiguationConfig getDisambiguation() {
        if (disambiguation == null) {
            disambiguation = new DisambiguationConfig();
        }
        return disambiguation;
    }
}