package com.yourco.ivr.domain.config;

import lombok.Data;

import java.util.List;

/**
 * Declares how multiple risk signals are combined into a single final
 * {@link com.yourco.ivr.domain.RiskLevel} for policy lookup.
 *
 * <p>Add this block to any brand's JSON config:</p>
 * <pre>
 * "riskCombination": {
 *   "strategy": "MATRIX",
 *   "matrixRules": [
 *     { "conditions": { "PHONE": "HIGH", "DEVICE": "HIGH" }, "result": "CRITICAL" },
 *     { "conditions": { "PHONE": "CRITICAL" },               "result": "CRITICAL" }
 *   ]
 * }
 * </pre>
 *
 * <h3>Strategies</h3>
 * <ul>
 *   <li><b>MAX</b> (default) — take the highest {@link com.yourco.ivr.domain.RiskLevel}
 *       across all active signal providers. Safe conservative default.</li>
 *   <li><b>MATRIX</b> — evaluate {@link #matrixRules} in order; first matching rule wins.
 *       Falls back to MAX if no rule matches. Use when you need cross-signal combinations
 *       ("only critical if phone AND device are both high").</li>
 * </ul>
 *
 * <p>Omitting this block entirely is equivalent to {@code strategy: "MAX"}.</p>
 */
@Data
public class RiskCombinationConfig {

    /**
     * Combination strategy. {@code "MAX"} or {@code "MATRIX"}.
     * Defaults to {@code "MAX"} when absent.
     */
    private String strategy = "MAX";

    /**
     * Ordered list of matrix rules. Only used when {@code strategy} is {@code "MATRIX"}.
     * Rules are evaluated top-to-bottom; the first rule whose conditions all match wins.
     */
    private List<MatrixRule> matrixRules;
}
