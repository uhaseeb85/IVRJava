package com.yourco.ivr;

import com.yourco.ivr.api.dto.AuthenticateRequest;
import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.api.dto.ErrorResponse;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.RiskAssessment;
import com.yourco.ivr.domain.RiskLevel;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.partyrisk.DeviceRiskProvider;
import com.yourco.ivr.partyrisk.PhoneRiskProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration tests for composite (multi-signal) risk assessment using RISK_COMBO_BRAND.
 *
 * Brand config (config/brands/risk_combo_brand.json) declares MATRIX strategy:
 *   PHONE=HIGH AND DEVICE=HIGH → CRITICAL (reject)
 *   PHONE=CRITICAL             → CRITICAL (reject)
 *   DEVICE=CRITICAL            → CRITICAL (reject)
 *   PHONE=HIGH (device < HIGH) → HIGH  → minimumTargetLevel=ELEVATED
 *   DEVICE=HIGH (phone < HIGH) → MEDIUM → blockedTokens=[DATE_OF_BIRTH]
 *   anything else              → MAX fallback (both LOW → LOW → no restriction)
 *
 * Both PhoneRiskProvider and DeviceRiskProvider are @MockBean so each test
 * controls both signals independently.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompositeRiskTest {

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private PhoneRiskProvider phoneRiskProvider;

    @MockBean
    private DeviceRiskProvider deviceRiskProvider;

    private static final String BRAND  = "RISK_COMBO_BRAND";
    private static final String CALLER = "5558887777";

    private RiskAssessment assessment(RiskLevel level) {
        RiskAssessment a = new RiskAssessment();
        a.setLevel(level);
        a.setFlags(Collections.emptyList());
        return a;
    }

    @BeforeEach
    void defaultBothToLow() {
        // Stub providerId() — Mockito does not invoke default interface methods
        when(phoneRiskProvider.providerId()).thenReturn("PHONE");
        when(deviceRiskProvider.providerId()).thenReturn("DEVICE");
        // Both LOW by default; individual tests override
        when(phoneRiskProvider.assess(anyString(), anyString())).thenReturn(assessment(RiskLevel.LOW));
        when(deviceRiskProvider.assess(anyString(), anyString())).thenReturn(assessment(RiskLevel.LOW));
    }

    private AuthenticateRequest startReq(AuthLevel target) {
        AuthenticateRequest req = new AuthenticateRequest();
        req.setBrandId(BRAND);
        req.setCallerId(CALLER);
        req.setTargetLevel(target);
        return req;
    }

    private ResponseEntity<AuthenticateResponse> post(AuthenticateRequest req) {
        return rest.postForEntity("/ivr/authenticate", req, AuthenticateResponse.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. MATRIX rule: PHONE=HIGH AND DEVICE=HIGH → composite CRITICAL → reject
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void phoneHighAndDeviceHighProducesCriticalAndIsRejected() {
        when(phoneRiskProvider.assess(anyString(), anyString())).thenReturn(assessment(RiskLevel.HIGH));
        when(deviceRiskProvider.assess(anyString(), anyString())).thenReturn(assessment(RiskLevel.HIGH));

        ResponseEntity<ErrorResponse> resp = rest.postForEntity(
            "/ivr/authenticate", startReq(AuthLevel.STANDARD), ErrorResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().getCode()).isEqualTo("HIGH_RISK_CALLER");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. MATRIX rule: PHONE=HIGH alone (DEVICE=LOW) → composite HIGH → level upgrade
    //    The rule "PHONE=HIGH AND DEVICE=HIGH" does NOT match; next rule "PHONE=HIGH" does.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void phoneHighAloneEscalatesTargetToElevated() {
        when(phoneRiskProvider.assess(anyString(), anyString())).thenReturn(assessment(RiskLevel.HIGH));
        // deviceRiskProvider stays LOW (default)

        ResponseEntity<AuthenticateResponse> resp = post(startReq(AuthLevel.STANDARD));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getTargetLevel()).isEqualTo(AuthLevel.ELEVATED);
        // riskLevel reflects the composite result
        assertThat(resp.getBody().getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        // Both per-provider signals are visible in the response
        assertThat(resp.getBody().getRiskSignals()).containsEntry("PHONE", RiskLevel.HIGH);
        assertThat(resp.getBody().getRiskSignals()).containsEntry("DEVICE", RiskLevel.LOW);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. MATRIX rule: DEVICE=HIGH alone (PHONE=LOW) → composite MEDIUM
    //    MEDIUM policy: blockedTokens=[DATE_OF_BIRTH], no level upgrade
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void deviceHighAloneProducesMediumAndBlocksDateOfBirth() {
        // phoneRiskProvider stays LOW (default)
        when(deviceRiskProvider.assess(anyString(), anyString())).thenReturn(assessment(RiskLevel.HIGH));

        ResponseEntity<AuthenticateResponse> resp = post(startReq(AuthLevel.STANDARD));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        // Target level not upgraded — MEDIUM policy has no minimumTargetLevel
        assertThat(resp.getBody().getTargetLevel()).isEqualTo(AuthLevel.STANDARD);
        // DATE_OF_BIRTH must not be offered (MEDIUM policy blocks it)
        assertThat(resp.getBody().getNextRequiredToken()).isNotEqualTo(com.yourco.ivr.domain.TokenType.DATE_OF_BIRTH);
        if (resp.getBody().getAcceptedTokens() != null) {
            assertThat(resp.getBody().getAcceptedTokens())
                .doesNotContain(com.yourco.ivr.domain.TokenType.DATE_OF_BIRTH);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. No matrix rule matches → MAX fallback → both LOW → LOW → no restriction
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void bothLowFallsBackToMaxAndProducesLow() {
        // Both providers stay LOW (default)

        ResponseEntity<AuthenticateResponse> resp = post(startReq(AuthLevel.STANDARD));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getRiskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(resp.getBody().getTargetLevel()).isEqualTo(AuthLevel.STANDARD);
        assertThat(resp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        // Both signals present in breakdown
        assertThat(resp.getBody().getRiskSignals()).containsEntry("PHONE", RiskLevel.LOW);
        assertThat(resp.getBody().getRiskSignals()).containsEntry("DEVICE", RiskLevel.LOW);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. PHONE=CRITICAL alone → composite CRITICAL via specific rule → reject
    //    (verifies at-or-above semantics: PHONE=CRITICAL satisfies the "PHONE=CRITICAL" rule)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void phoneCriticalAloneIsRejectedViaSingleSignalRule() {
        when(phoneRiskProvider.assess(anyString(), anyString())).thenReturn(assessment(RiskLevel.CRITICAL));
        // deviceRiskProvider stays LOW (default)

        ResponseEntity<ErrorResponse> resp = rest.postForEntity(
            "/ivr/authenticate", startReq(AuthLevel.STANDARD), ErrorResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().getCode()).isEqualTo("HIGH_RISK_CALLER");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. PHONE=MEDIUM, DEVICE=MEDIUM → no matrix rule fires → MAX → MEDIUM
    //    MEDIUM policy blocks DATE_OF_BIRTH but allows normal auth otherwise
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void bothMediumFallsBackToMaxMediumPolicy() {
        when(phoneRiskProvider.assess(anyString(), anyString())).thenReturn(assessment(RiskLevel.MEDIUM));
        when(deviceRiskProvider.assess(anyString(), anyString())).thenReturn(assessment(RiskLevel.MEDIUM));

        ResponseEntity<AuthenticateResponse> resp = post(startReq(AuthLevel.STANDARD));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        // No level upgrade from MEDIUM policy
        assertThat(resp.getBody().getTargetLevel()).isEqualTo(AuthLevel.STANDARD);
    }
}
