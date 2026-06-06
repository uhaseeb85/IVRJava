package com.yourco.ivr.domain;

/**
 * Coarse phase of an {@link IvrSession}, tracking whether the engine is still resolving
 * which customer is calling or has moved on to collecting authentication credentials.
 *
 * <p>A session starts in {@link #DISAMBIGUATION} when ANI lookup returns multiple
 * candidate parties. It advances to {@link #AUTHENTICATING} once
 * {@link com.yourco.ivr.engine.DisambiguationEngine} resolves a single matched party.
 * Sessions with exactly one party skip DISAMBIGUATION entirely and start in AUTHENTICATING.
 */
public enum SessionPhase {
    /**
     * The caller's ANI matched more than one party. The engine is collecting identifying
     * tokens (e.g. account number, date of birth) to narrow down to a single party before
     * authentication can begin.
     */
    DISAMBIGUATION,
    /**
     * A unique party has been identified. The engine is now collecting and validating
     * authentication credentials per the brand's level rules.
     */
    AUTHENTICATING
}
