package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.RiskLevel;
import lombok.Data;

import java.util.Map;

/**
 * A single row in a brand's risk combination matrix.
 *
 * <p>A rule matches when ALL of its {@link #conditions} are satisfied — i.e.
 * the actual signal level is <em>at or above</em> the required level (using
 * {@link RiskLevel} ordinal comparison). Provider IDs not listed in
 * {@link #conditions} are ignored.</p>
 *
 * <p>Rules are evaluated in declaration order; the first match wins.
 * If no rule matches, the combination strategy falls back to MAX.</p>
 *
 * <p>Example — classify as CRITICAL only when BOTH phone AND device are HIGH or above:</p>
 * <pre>
 * {
 *   "conditions": { "PHONE": "HIGH", "DEVICE": "HIGH" },
 *   "result": "CRITICAL"
 * }
 * </pre>
 */
@Data
public class MatrixRule {

    /**
     * Map of providerId → minimum required {@link RiskLevel}.
     * All entries must match (AND logic) for this rule to fire.
     * Comparison is at-or-above: a condition of HIGH matches HIGH and CRITICAL.
     */
    private Map<String, RiskLevel> conditions;

    /** The composite {@link RiskLevel} produced when this rule matches. */
    private RiskLevel result;
}
