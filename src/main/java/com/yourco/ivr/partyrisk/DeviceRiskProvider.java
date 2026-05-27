package com.yourco.ivr.partyrisk;

import com.yourco.ivr.domain.RiskAssessment;

/**
 * SPI for VoIP / device-level risk scoring.
 *
 * <p>Replace {@link StubDeviceRiskProvider} with a real implementation that
 * calls your carrier analytics or SIP-fingerprinting service.
 * Signals like "VOIP_NUMBER", "CALL_FORWARDED", or "VIRTUAL_NUMBER" can be
 * surfaced as flags and used in brand-config {@code matrixRules}.</p>
 *
 * <p>Signal ID: {@code "DEVICE"}</p>
 */
public interface DeviceRiskProvider extends RiskSignalProvider {

    default String providerId() { return "DEVICE"; }

    RiskAssessment assess(String callerId, String brandId);
}
