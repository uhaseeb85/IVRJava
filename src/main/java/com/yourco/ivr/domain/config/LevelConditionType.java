package com.yourco.ivr.domain.config;

/**
 * Condition types usable inside a {@link LevelSelectionRule}.
 *
 * <p>Each condition is evaluated against the call context available once a party has
 * been resolved (matched party + customer preferences). The supported set is deliberately
 * small and data-driven so brand configs can express "which auth level the system should
 * naturally lead the customer to" without code changes:
 * <ul>
 *   <li>{@link #ANI_MATCHED} — true when the caller's ANI resolved to at least one party
 *       (always true at the point level determination runs, since an empty lookup is
 *       rejected earlier).</li>
 *   <li>{@link #PARTY_ACTIVE} — true when the matched party's account is active.</li>
 *   <li>{@link #PRIMARY_ANI} — true when the caller's number is the party's primary ANI.</li>
 *   <li>{@link #PARTY_ATTRIBUTE} — true when the matched party's
 *       {@code additionalAttributes} contains {@code key} with the exact {@code value}.
 *       Used for integration-specific attributes such as customer segment or party type.</li>
 * </ul>
 *
 * <p>Rules with an empty condition list always match, which makes them suitable as a
 * catch-all before {@link LevelDeterminationConfig#getDefaultLevel()} is consulted.
 */
public enum LevelConditionType {
    ANI_MATCHED,
    PARTY_ACTIVE,
    PRIMARY_ANI,
    PARTY_ATTRIBUTE
}
