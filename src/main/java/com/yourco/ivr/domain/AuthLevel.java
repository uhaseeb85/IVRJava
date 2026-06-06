package com.yourco.ivr.domain;

/**
 * Ordered hierarchy of authentication levels an IVR session can achieve.
 *
 * <p>Each level has an integer rank so levels can be compared without hardcoding enum ordinals.
 * A session starts at {@link #NONE} and progresses upward as the caller satisfies the token
 * requirements defined in the brand config for each level.
 *
 * <p>Levels map to {@link com.yourco.ivr.domain.config.LevelRule} entries in a
 * {@link com.yourco.ivr.domain.config.BrandAuthConfig}. A brand does not need to define rules
 * for every level — only the levels it actually uses.
 */
public enum AuthLevel {
    /** No authentication performed; initial state of every session. */
    NONE(0),
    /** Lightweight credential check (e.g. account number only). */
    BASIC(1),
    /** Standard two-factor authentication (e.g. account number + PIN). */
    STANDARD(2),
    /** High-assurance authentication requiring additional proof (e.g. OTP or voice print). */
    ELEVATED(3),
    /** Maximum assurance level, typically restricted to internal or administrative flows. */
    ADMIN(4);

    private final int rank;

    AuthLevel(int rank) {
        this.rank = rank;
    }

    /** Numeric rank used for ordering comparisons; higher rank = higher assurance. */
    public int getRank() {
        return rank;
    }

    /**
     * Returns {@code true} if this level represents greater assurance than {@code other}.
     * Used by {@link com.yourco.ivr.engine.AuthEngine#escalate} to guard against
     * downgrade requests.
     */
    public boolean isHigherThan(AuthLevel other) {
        return this.rank > other.rank;
    }
}
