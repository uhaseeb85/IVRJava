package com.yourco.ivr.partyrisk;

import com.yourco.ivr.domain.RiskAssessment;

/**
 * Generic SPI for any caller-risk signal source.
 *
 * <p>Implement this interface and annotate with {@code @Component} to register
 * a new risk dimension. The {@link RiskSignalRegistry} auto-discovers all
 * implementations at startup — no other wiring is required.</p>
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link PhoneRiskProvider} / {@link StubPhoneRiskProvider} — ANI / phone-line risk</li>
 *   <li>{@link DeviceRiskProvider} / {@link StubDeviceRiskProvider} — VoIP / device risk</li>
 * </ul></p>
 *
 * <p>How signals are combined into a final {@link com.yourco.ivr.domain.RiskLevel} is
 * declared in each brand's {@code riskCombination} config block. The default strategy
 * is {@code MAX} (highest signal wins). {@code MATRIX} lets brands define explicit
 * combination rules, e.g. "if PHONE=HIGH AND DEVICE=HIGH → CRITICAL".</p>
 */
public interface RiskSignalProvider {

    /**
     * Stable identifier for this signal source, used as the map key in
     * {@link com.yourco.ivr.domain.CompositeRiskAssessment#getSignals()} and
     * referenced in brand-config {@code matrixRules}.
     *
     * <p>Examples: {@code "PHONE"}, {@code "DEVICE"}, {@code "ACCOUNT"}.</p>
     */
    String providerId();

    /**
     * Score the caller. Never throw — if the upstream is unavailable, return
     * {@link com.yourco.ivr.domain.RiskLevel#LOW} with an explanatory flag
     * (e.g. {@code "PROVIDER_UNAVAILABLE"}) rather than propagating the exception.
     *
     * @param callerId ANI of the caller
     * @param brandId  brand receiving the call
     * @return non-null {@link RiskAssessment}
     */
    RiskAssessment assess(String callerId, String brandId);
}
