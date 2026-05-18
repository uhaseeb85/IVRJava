package com.yourco.ivr;

import com.yourco.ivr.api.dto.CallTransferRequest;
import com.yourco.ivr.api.dto.EscalateRequest;
import com.yourco.ivr.api.dto.SessionResponse;
import com.yourco.ivr.api.dto.StartSessionRequest;
import com.yourco.ivr.api.dto.TokenSubmitRequest;
import com.yourco.ivr.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IvrAuthIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void testFullAuthFlow() {
        // Start session
        StartSessionRequest startReq = new StartSessionRequest();
        startReq.setBrandId("BRAND_A");
        startReq.setCallerId("5551234567");
        startReq.setTargetLevel(AuthLevel.STANDARD);

        ResponseEntity<SessionResponse> startResp = rest.postForEntity(
            "/ivr/session/start", startReq, SessionResponse.class);
        assertThat(startResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(startResp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(startResp.getBody().getNextRequiredToken()).isEqualTo(TokenType.ACCOUNT_NUMBER);

        String sessionId = startResp.getBody().getSessionId();
        assertThat(sessionId).isNotNull();

        // Submit ACCOUNT_NUMBER
        TokenSubmitRequest tokenReq = new TokenSubmitRequest();
        tokenReq.setTokenType(TokenType.ACCOUNT_NUMBER);
        tokenReq.setTokenValue("123456789");

        ResponseEntity<SessionResponse> tokenResp = rest.postForEntity(
            "/ivr/session/" + sessionId + "/token", tokenReq, SessionResponse.class);
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(tokenResp.getBody().getNextRequiredToken()).isEqualTo(TokenType.PIN);

        // Submit PIN
        tokenReq.setTokenType(TokenType.PIN);
        tokenReq.setTokenValue("1234");

        tokenResp = rest.postForEntity(
            "/ivr/session/" + sessionId + "/token", tokenReq, SessionResponse.class);
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResp.getBody().getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(tokenResp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.STANDARD);
    }

    @Test
    void testBackupTokenFlow() {
        StartSessionRequest startReq = new StartSessionRequest();
        startReq.setBrandId("BRAND_A");
        startReq.setCallerId("5557654321");
        startReq.setTargetLevel(AuthLevel.STANDARD);

        ResponseEntity<SessionResponse> startResp = rest.postForEntity(
            "/ivr/session/start", startReq, SessionResponse.class);
        String sessionId = startResp.getBody().getSessionId();

        // Submit ACCOUNT_NUMBER
        TokenSubmitRequest tokenReq = new TokenSubmitRequest();
        tokenReq.setTokenType(TokenType.ACCOUNT_NUMBER);
        tokenReq.setTokenValue("123456789");
        rest.postForEntity("/ivr/session/" + sessionId + "/token", tokenReq, SessionResponse.class);

        // Submit SSN_LAST4 as backup for PIN
        tokenReq.setTokenType(TokenType.SSN_LAST4);
        tokenReq.setTokenValue("1234");

        ResponseEntity<SessionResponse> tokenResp = rest.postForEntity(
            "/ivr/session/" + sessionId + "/token", tokenReq, SessionResponse.class);
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResp.getBody().getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(tokenResp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.STANDARD);
    }

    @Test
    void testFallbackPathFlow() {
        StartSessionRequest startReq = new StartSessionRequest();
        startReq.setBrandId("BRAND_A");
        startReq.setCallerId("5551112222");
        startReq.setTargetLevel(AuthLevel.STANDARD);

        ResponseEntity<SessionResponse> startResp = rest.postForEntity(
            "/ivr/session/start", startReq, SessionResponse.class);
        String sessionId = startResp.getBody().getSessionId();

        // Submit ACCOUNT_NUMBER
        TokenSubmitRequest tokenReq = new TokenSubmitRequest();
        tokenReq.setTokenType(TokenType.ACCOUNT_NUMBER);
        tokenReq.setTokenValue("123456789");
        rest.postForEntity("/ivr/session/" + sessionId + "/token", tokenReq, SessionResponse.class);

        // Submit wrong PIN 3 times to exhaust retries and trigger fallback
        tokenReq.setTokenType(TokenType.PIN);
        tokenReq.setTokenValue("12"); // too short, will fail validation

        ResponseEntity<SessionResponse> tokenResp = null;
        for (int i = 0; i < 3; i++) {
            tokenResp = rest.postForEntity(
                "/ivr/session/" + sessionId + "/token", tokenReq, SessionResponse.class);
        }

        // After 3 failures, should fallback to OTP path
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(tokenResp.getBody().getNextRequiredToken()).isEqualTo(TokenType.OTP);

        // Submit valid OTP
        tokenReq.setTokenType(TokenType.OTP);
        tokenReq.setTokenValue("123456");

        tokenResp = rest.postForEntity(
            "/ivr/session/" + sessionId + "/token", tokenReq, SessionResponse.class);
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResp.getBody().getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(tokenResp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.STANDARD);
    }

    @Test
    void testGetStatus() {
        StartSessionRequest startReq = new StartSessionRequest();
        startReq.setBrandId("BRAND_A");
        startReq.setCallerId("5553334444");
        startReq.setTargetLevel(AuthLevel.BASIC);

        ResponseEntity<SessionResponse> startResp = rest.postForEntity(
            "/ivr/session/start", startReq, SessionResponse.class);
        String sessionId = startResp.getBody().getSessionId();

        ResponseEntity<SessionResponse> statusResp = rest.getForEntity(
            "/ivr/session/" + sessionId + "/status", SessionResponse.class);
        assertThat(statusResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testDeleteSession() {
        StartSessionRequest startReq = new StartSessionRequest();
        startReq.setBrandId("BRAND_A");
        startReq.setCallerId("5555555555");
        startReq.setTargetLevel(AuthLevel.BASIC);

        ResponseEntity<SessionResponse> startResp = rest.postForEntity(
            "/ivr/session/start", startReq, SessionResponse.class);
        String sessionId = startResp.getBody().getSessionId();

        rest.delete("/ivr/session/" + sessionId);

        ResponseEntity<SessionResponse> statusResp = rest.getForEntity(
            "/ivr/session/" + sessionId + "/status", SessionResponse.class);
        assertThat(statusResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void testInitialTokens() {
        StartSessionRequest startReq = new StartSessionRequest();
        startReq.setBrandId("BRAND_A");
        startReq.setCallerId("5556667777");
        startReq.setTargetLevel(AuthLevel.STANDARD);
        startReq.setInitialTokens(new java.util.HashMap<>());
        startReq.getInitialTokens().put(TokenType.ACCOUNT_NUMBER, "123456789");

        ResponseEntity<SessionResponse> startResp = rest.postForEntity(
            "/ivr/session/start", startReq, SessionResponse.class);
        assertThat(startResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // ACCOUNT_NUMBER was pre-submitted, next should be PIN
        assertThat(startResp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(startResp.getBody().getNextRequiredToken()).isEqualTo(TokenType.PIN);
    }

    @Test
    void testUnknownBrand() {
        StartSessionRequest startReq = new StartSessionRequest();
        startReq.setBrandId("UNKNOWN");
        startReq.setCallerId("5550000000");
        startReq.setTargetLevel(AuthLevel.BASIC);

        ResponseEntity<String> resp = rest.postForEntity(
            "/ivr/session/start", startReq, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Call Transfer Tests ──────────────────────────────────────────────────

    @Test
    void testTransferHappyPath() {
        // LEGACY_IVR has validated ACCOUNT_NUMBER at BASIC, target STANDARD
        CallTransferRequest req = new CallTransferRequest();
        req.setSourceSystemId("LEGACY_IVR");
        req.setBrandId("BRAND_A");
        req.setCallerId("5551234567");
        req.setCurrentLevel(AuthLevel.BASIC);
        req.setTargetLevel(AuthLevel.STANDARD);
        req.setValidatedTokens(Arrays.asList(TokenType.ACCOUNT_NUMBER));

        ResponseEntity<SessionResponse> resp = rest.postForEntity(
            "/ivr/session/transfer", req, SessionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        // ACCOUNT_NUMBER already validated, next should be PIN
        assertThat(resp.getBody().getNextRequiredToken()).isEqualTo(TokenType.PIN);
        assertThat(resp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.BASIC);
    }

    @Test
    void testTransferAlreadyAtTargetLevel() {
        // LEGACY_IVR has validated ACCOUNT_NUMBER, target BASIC → already there
        CallTransferRequest req = new CallTransferRequest();
        req.setSourceSystemId("LEGACY_IVR");
        req.setBrandId("BRAND_A");
        req.setCallerId("5552223333");
        req.setCurrentLevel(AuthLevel.NONE);
        req.setTargetLevel(AuthLevel.BASIC);
        req.setValidatedTokens(Arrays.asList(TokenType.ACCOUNT_NUMBER));

        ResponseEntity<SessionResponse> resp = rest.postForEntity(
            "/ivr/session/transfer", req, SessionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // ACCOUNT_NUMBER satisfies BASIC level fully
        assertThat(resp.getBody().getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(resp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.BASIC);
    }

    @Test
    void testTransferTokenFiltering() {
        // SALESFORCE only honors ACCOUNT_NUMBER, not PIN
        CallTransferRequest req = new CallTransferRequest();
        req.setSourceSystemId("SALESFORCE");
        req.setBrandId("BRAND_A");
        req.setCallerId("5553334444");
        req.setCurrentLevel(AuthLevel.NONE);
        req.setTargetLevel(AuthLevel.STANDARD);
        req.setValidatedTokens(Arrays.asList(TokenType.ACCOUNT_NUMBER, TokenType.PIN));

        ResponseEntity<SessionResponse> resp = rest.postForEntity(
            "/ivr/session/transfer", req, SessionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // PIN should NOT have been honored — so next token is PIN
        assertThat(resp.getBody().getNextRequiredToken()).isEqualTo(TokenType.PIN);
    }

    @Test
    void testTransferLevelCapping() {
        // SALESFORCE maxHonoredLevel is BASIC, but request claims STANDARD
        CallTransferRequest req = new CallTransferRequest();
        req.setSourceSystemId("SALESFORCE");
        req.setBrandId("BRAND_A");
        req.setCallerId("5554445555");
        req.setCurrentLevel(AuthLevel.STANDARD);
        req.setTargetLevel(AuthLevel.STANDARD);
        req.setValidatedTokens(Arrays.asList(TokenType.ACCOUNT_NUMBER));

        ResponseEntity<SessionResponse> resp = rest.postForEntity(
            "/ivr/session/transfer", req, SessionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Current level should be capped at BASIC (maxHonoredLevel)
        assertThat(resp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.BASIC);
    }

    @Test
    void testTransferUnknownSource() {
        CallTransferRequest req = new CallTransferRequest();
        req.setSourceSystemId("UNKNOWN_SYSTEM");
        req.setBrandId("BRAND_A");
        req.setCallerId("5555556666");
        req.setCurrentLevel(AuthLevel.NONE);
        req.setTargetLevel(AuthLevel.BASIC);
        req.setValidatedTokens(Arrays.asList(TokenType.ACCOUNT_NUMBER));

        ResponseEntity<String> resp = rest.postForEntity(
            "/ivr/session/transfer", req, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
