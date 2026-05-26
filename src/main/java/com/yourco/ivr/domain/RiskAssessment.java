package com.yourco.ivr.domain;

import lombok.Data;

import java.util.List;

/**
 * Result of a phone-risk lookup for the calling ANI.
 * Stored on {@link IvrSession} and surfaced in every {@code AuthenticateResponse}
 * so the IVR platform can play risk-appropriate prompts.
 */
@Data
public class RiskAssessment {

    /** Assessed risk level for this caller. */
    private RiskLevel level;

    /**
     * Human-readable risk signals from the provider.
     * Examples: "RECENTLY_PORTED", "SPOOFED_ANI", "ASSOCIATED_WITH_FRAUD".
     * Empty list when no flags are raised.
     */
    private List<String> flags;
}
