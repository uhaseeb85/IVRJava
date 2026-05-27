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
     * Applied after the combination strategy has produced a single final level.
     *
     * <pre>
     * "riskPolicies": {
     *   "HIGH":     { "minimumTargetLevel": "ELEVATED", "blockedTokens": ["PIN"] },
     *   "CRITICAL": { "reject": true }
     * }
     * </pre>
     */
    private Map<RiskLevel, RiskPolicy> riskPolicies;

    /**
     * Declares how multiple risk signals are combined into a single final
     * {@link RiskLevel} before {@link #riskPolicies} is consulted.
     * Omit this block to use the default MAX strategy (highest signal wins).
     *
     * <pre>
     * "riskCombination": {
     *   "strategy": "MATRIX",
     *   "matrixRules": [
     *     { "conditions": { "PHONE": "HIGH", "DEVICE": "HIGH" }, "result": "CRITICAL" }
     *   ]
     * }
     * </pre>
     */
    private RiskCombinationConfig riskCombination;

    public DisambiguationConfig getDisambiguation() {
        if (disambiguation == null) {
            disambiguation = new DisambiguationConfig();
        }
        return disambiguation;
    }
}