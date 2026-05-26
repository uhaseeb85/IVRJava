package com.yourco.ivr.partyrisk;

import com.yourco.ivr.domain.RiskAssessment;
import com.yourco.ivr.domain.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Stub implementation that always returns {@link RiskLevel#LOW} with no flags.
 *
 * <p>Replace with a real implementation before going to production.
 * See {@link PhoneRiskProvider} for instructions.</p>
 */
@Component
public class StubPhoneRiskProvider implements PhoneRiskProvider {

    @Override
    public RiskAssessment assess(String callerId, String brandId) {
        RiskAssessment assessment = new RiskAssessment();
        assessment.setLevel(RiskLevel.LOW);
        assessment.setFlags(Collections.emptyList());
        return assessment;
    }
}
