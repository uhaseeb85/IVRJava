package com.yourco.ivr.exception;

import com.yourco.ivr.domain.RiskLevel;

/**
 * Thrown when a caller's risk level matches a brand policy that mandates rejection.
 * Maps to HTTP 403 in {@link com.yourco.ivr.api.IvrExceptionHandler}.
 */
public class HighRiskCallerException extends RuntimeException {

    private final RiskLevel riskLevel;

    public HighRiskCallerException(String callerId, RiskLevel riskLevel) {
        super("Caller rejected due to risk level " + riskLevel + ": " + callerId);
        this.riskLevel = riskLevel;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }
}
