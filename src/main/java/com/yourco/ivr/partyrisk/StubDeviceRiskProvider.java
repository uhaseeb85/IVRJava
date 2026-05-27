package com.yourco.ivr.partyrisk;

import com.yourco.ivr.domain.RiskAssessment;
import com.yourco.ivr.domain.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Stub implementation that always returns {@link RiskLevel#LOW} with no flags.
 *
 * <p>Replace with a real implementation before going to production.
 * See {@link DeviceRiskProvider} for instructions.</p>
 */
@Component
public class StubDeviceRiskProvider implements DeviceRiskProvider {

    @Override
    public RiskAssessment assess(String callerId, String brandId) {
        RiskAssessment assessment = new RiskAssessment();
        assessment.setLevel(RiskLevel.LOW);
        assessment.setFlags(Collections.emptyList());
        return assessment;
    }
}
