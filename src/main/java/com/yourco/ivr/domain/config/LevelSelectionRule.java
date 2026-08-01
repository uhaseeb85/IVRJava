package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.AuthLevel;
import lombok.Data;

import java.util.List;

/**
 * One entry in a brand's {@code levelDetermination.rules} list.
 *
 * <p>Rules are evaluated in order; the first rule whose conditions all pass determines the
 * authentication level the system leads the customer to. A rule with an empty (or null)
 * condition list always matches and can be used as an explicit catch-all.
 *
 * <p>Example JSON:
 * <pre>
 * {
 *   "description": "Premium segment customers",
 *   "level": "ELEVATED",
 *   "conditions": [ { "type": "PARTY_ATTRIBUTE", "key": "segment", "value": "PREMIUM" } ]
 * }
 * </pre>
 */
@Data
public class LevelSelectionRule {
    /** Human-readable label for the rule, surfaced in the processing log when it matches. */
    private String description;

    /** The authentication level this rule selects when all conditions pass. */
    private AuthLevel level;

    /** Ordered predicates; all must pass for the rule to match. Empty list = always match. */
    private List<LevelCondition> conditions;
}
