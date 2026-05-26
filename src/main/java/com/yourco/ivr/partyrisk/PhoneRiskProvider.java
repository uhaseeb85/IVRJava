package com.yourco.ivr.partyrisk;

import com.yourco.ivr.domain.RiskAssessment;

/**
 * SPI for ANI-based phone risk assessment.
 *
 * <p>Implement and annotate with {@code @Component} (removing {@code @Component}
 * from {@link StubPhoneRiskProvider}) to connect to a real risk-scoring service
 * such as Pindrop, Neustar, or TransUnion TLOxp.</p>
 *
 * <p>The result is attached to the session and used by
 * {@link com.yourco.ivr.service.AuthenticateService} to enforce the brand's
 * {@code riskPolicies} before any authentication steps begin.</p>
 */
public interface PhoneRiskProvider {

    /**
     * Assess the risk of the calling phone number.
     *
     * @param callerId the ANI (caller ID) as received by the IVR platform
     * @param brandId  the brand context — some providers vary scoring by brand
     * @return a non-null {@link RiskAssessment}; never throw for lookup failures,
     *         return {@code RiskLevel.LOW} with an explanatory flag instead
     */
    RiskAssessment assess(String callerId, String brandId);
}
