package com.yourco.ivr.domain;

/**
 * Caller risk levels, ordered from lowest to highest concern.
 * Produced by {@link com.yourco.ivr.partyrisk.PhoneRiskProvider} and consumed
 * by the brand's {@code riskPolicies} map in
 * {@link com.yourco.ivr.domain.config.BrandAuthConfig}.
 */
public enum RiskLevel {
    LOW, MEDIUM, HIGH, CRITICAL
}
