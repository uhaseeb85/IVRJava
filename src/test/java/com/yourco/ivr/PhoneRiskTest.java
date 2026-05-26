package com.yourco.ivr;

import com.yourco.ivr.api.dto.AuthenticateRequest;
import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.api.dto.ErrorResponse;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.RiskAssessment;
import com.yourco.ivr.domain.RiskLevel;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
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
 * Integration tests for phone-risk-aware session start and token flow.
 *
 * The RISK_TEST_BRAND brand config (config/brands/risk_test_brand.json) defines:
 *   HIGH  → minimumTargetLevel=ELEVATED, blockedTokens=[DATE_OF_BIRTH]
 *   CRITICAL → reject=true
 *
 * StubPhoneRiskProvider is replaced by a @MockBean so each test controls the
 * risk level returned for the caller under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PhoneRiskTest {

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private PhoneRiskProvider riskProvider;

    private static final String BRAND = "RISK_TEST_BRAND";
    private static final String CALLER = "5559998888";

    private RiskAssessment assessment(RiskLevel level) {
        RiskAssessment a = new RiskAssessment();
        a.setLevel(level);
        a.setFlags(Collections.emptyList());
        return a;
    }

    @BeforeEach
    void defaultToLow() {
        // safe default — individual tests override as needed
        when(riskProvider.assess(anyString(), anyString())).thenReturn(assessment(RiskLevel.LOW));
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
    // 1. CRITICAL caller is rejected before any session is created
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void criticalCallerIsRejectedWith403() {
        when(riskProvider.assess(anyString(), anyString())).thenReturn(assessment(RiskLevel.CRITICAL));

        ResponseEntity<ErrorResponse> resp = rest.postForEntity(
            "/ivr/authenticate", startReq(AuthLevel.STANDARD), ErrorResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo("HIGH_RISK_CALLER");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. HIGH caller: target level is silently upgraded to ELEVATED
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void highRiskCallerHasTargetLevelUpgradedToElevated() {
        when(riskProvider.assess(anyString(), anyString())).thenReturn(assessment(RiskLevel.HIGH));

        // Request STANDARD, but the HIGH risk policy forces ELEVATED
        ResponseEntity<AuthenticateResponse> resp = post(startReq(AuthLevel.STANDARD));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTargetLevel()).isEqualTo(AuthLevel.ELEVATED);
        assertThat(resp.getBody().getRiskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. HIGH caller: DATE_OF_BIRTH is blocked — it must not appear as a
    //    next required or accepted token even if the path allows it
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void highRiskCallerHasDateOfBirthBlocked() {
        when(riskProvider.assess(anyString(), anyString())).thenReturn(assessment(RiskLevel.HIGH));

        ResponseEntity<AuthenticateResponse> resp = post(startReq(AuthLevel.ELEVATED));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthenticateResponse body = resp.getBody();
        assertThat(body).isNotNull();

        // DATE_OF_BIRTH must not be offered as next required token
        assertThat(body.getNextRequiredToken()).isNotEqualTo(TokenType.DATE_OF_BIRTH);

        // DATE_OF_BIRTH must not appear in the accepted-tokens list either
        if (body.getAcceptedTokens() != null) {
            assertThat(body.getAcceptedTokens()).doesNotContain(TokenType.DATE_OF_BIRTH);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. LOW caller proceeds through normal flow and reaches AUTHENTICATED
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void lowRiskCallerCompletesNormalAuthFlow() {
        // LOW risk — default stub behaviour; no policy applies
        ResponseEntity<AuthenticateResponse> startResp = post(startReq(AuthLevel.STANDARD));
        assertThat(startResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(startResp.getBody().getTargetLevel()).isEqualTo(AuthLevel.STANDARD);
        assertThat(startResp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(startResp.getBody().getNextRequiredToken()).isEqualTo(TokenType.ACCOUNT_NUMBER);
        assertThat(startResp.getBody().getRiskLevel()).isEqualTo(RiskLevel.LOW);

        String sessionId = startResp.getBody().getSessionId();

        AuthenticateRequest token = new AuthenticateRequest();
        token.setSessionId(sessionId);
        token.setTokenType(TokenType.ACCOUNT_NUMBER);
        token.setTokenValue("123456789");
        ResponseEntity<AuthenticateResponse> r1 = post(token);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r1.getBody().getNextRequiredToken()).isEqualTo(TokenType.PIN);

        token.setTokenType(TokenType.PIN);
        token.setTokenValue("1234");
        ResponseEntity<AuthenticateResponse> r2 = post(token);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r2.getBody().getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(r2.getBody().getCurrentLevel()).isEqualTo(AuthLevel.STANDARD);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. MEDIUM caller: no policy defined → treated identically to LOW
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mediumRiskCallerWithNoPolicyProceedsNormally() {
        when(riskProvider.assess(anyString(), anyString())).thenReturn(assessment(RiskLevel.MEDIUM));

        ResponseEntity<AuthenticateResponse> resp = post(startReq(AuthLevel.STANDARD));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // No policy for MEDIUM in risk_test_brand → target level unchanged
        assertThat(resp.getBody().getTargetLevel()).isEqualTo(AuthLevel.STANDARD);
        assertThat(resp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
    }
}
