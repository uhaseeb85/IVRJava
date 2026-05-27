package com.yourco.ivr.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * Risk assessment that aggregates signals from multiple {@link com.yourco.ivr.partyrisk.RiskSignalProvider}
 * implementations into a single final {@link RiskLevel}.
 *
 * <p>{@link #getLevel()} is the final composite level after applying the brand's
 * combination strategy (MAX or MATRIX rules). {@link #getSignals()} exposes the
 * per-provider breakdown so the IVR platform can observe exactly which signal
 * drove the decision without the engine needing to know about it.</p>
 *
 * <p>Extends {@link RiskAssessment} so existing code that reads {@code session.getRiskAssessment().getLevel()}
 * requires no changes.</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CompositeRiskAssessment extends RiskAssessment {

    /**
     * Per-provider risk levels, keyed by {@link com.yourco.ivr.partyrisk.RiskSignalProvider#providerId()}.
     *
     * <p>Example: {@code {"PHONE": "HIGH", "DEVICE": "MEDIUM"}} — the combination
     * strategy then maps this to the final {@link RiskLevel} stored in {@link #getLevel()}.</p>
     */
    private Map<String, RiskLevel> signals;
}
