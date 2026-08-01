package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.AuthLevel;
import lombok.Data;

import java.util.List;

/**
 * Declarative "which auth level does this call need?" section of a brand config.
 *
 * <p>In a real IVR the system — not the caller — decides which authentication level the
 * customer must reach, based on call conditions (ANI match, party attributes, preferences).
 * This section encodes that decision as an ordered rule list evaluated once the caller's
 * party has been resolved:
 * <ol>
 *   <li>Walk {@link #getRules()} in order; the first rule whose conditions all pass wins.</li>
 *   <li>If no rule matches, fall back to {@link #getDefaultLevel()}.</li>
 *   <li>The derived level is then clamped to the customer's
 *       {@code preferences.maxAllowedLevel} cap and becomes the session's target level.</li>
 * </ol>
 *
 * <p>Brands that omit this section keep the legacy behavior: the caller-supplied
 * {@code targetLevel} in the start request is used as-is.
 *
 * <p>Example JSON:
 * <pre>
 * {
 *   "rules": [
 *     { "level": "ELEVATED", "conditions": [
 *         { "type": "PARTY_ATTRIBUTE", "key": "segment", "value": "PREMIUM" } ] },
 *     { "level": "STANDARD", "conditions": [ { "type": "ANI_MATCHED" } ] }
 *   ],
 *   "defaultLevel": "BASIC"
 * }
 * </pre>
 */
@Data
public class LevelDeterminationConfig {
    /** Ordered level-selection rules; first match wins. */
    private List<LevelSelectionRule> rules;

    /** Level used when no rule matches. Must be a level defined in the brand's {@code levelRules}. */
    private AuthLevel defaultLevel;
}
