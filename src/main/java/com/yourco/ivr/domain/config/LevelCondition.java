package com.yourco.ivr.domain.config;

import lombok.Data;

/**
 * A single predicate inside a {@link LevelSelectionRule}.
 *
 * <p>The {@code type} selects which aspect of the call context is inspected; {@code key}
 * and {@code value} are only meaningful for {@link LevelConditionType#PARTY_ATTRIBUTE}
 * (the attribute map key and the expected value respectively).
 *
 * <p>Example JSON:
 * <pre>
 * { "type": "PARTY_ATTRIBUTE", "key": "segment", "value": "PREMIUM" }
 * </pre>
 */
@Data
public class LevelCondition {
    /** Which aspect of the call context this condition inspects. */
    private LevelConditionType type;

    /** Attribute map key; required only when {@code type} is {@code PARTY_ATTRIBUTE}. */
    private String key;

    /** Expected value; required only when {@code type} is {@code PARTY_ATTRIBUTE}. */
    private String value;
}
