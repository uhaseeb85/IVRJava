package com.yourco.ivr.domain.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the party disambiguation phase.
 *
 * <p>When an ANI lookup returns multiple parties, the
 * {@link com.yourco.ivr.engine.DisambiguationEngine} applies the configured
 * {@code rules} (in order) to narrow the candidate list, then iteratively prompts the
 * caller for identifying tokens until a single party is matched.
 *
 * <p>Defaults: {@code maxDisambiguationTokens = 3}, empty rules list.
 */
@Data
public class DisambiguationConfig {
    /**
     * Maximum number of token-collection rounds the engine will attempt before giving up
     * and setting the session to {@link com.yourco.ivr.domain.SessionStatus#FAILED}.
     */
    private int maxDisambiguationTokens;

    /**
     * Pre-filter rules applied to the candidate party list at the start of disambiguation.
     * Supported types: {@code "EXCLUDE_INACTIVE"}, {@code "PREFER_PRIMARY_ANI"}.
     */
    private List<DisambiguationRuleConfig> rules;

    public DisambiguationConfig() {
        this.rules = new ArrayList<>();
        this.maxDisambiguationTokens = 3;
    }

    /** Config entry for a single named disambiguation rule. */
    @Data
    public static class DisambiguationRuleConfig {
        /**
         * Rule type name; maps to an implementation in
         * {@link com.yourco.ivr.engine.DisambiguationEngine#createRule(String)}.
         * Supported values: {@code "EXCLUDE_INACTIVE"}, {@code "PREFER_PRIMARY_ANI"}.
         */
        private String type;
    }
}
