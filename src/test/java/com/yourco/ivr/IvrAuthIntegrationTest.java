package com.yourco.ivr;

import com.yourco.ivr.api.dto.CallTransferRequest;
import com.yourco.ivr.api.dto.AuthenticateRequest;
import com.yourco.ivr.api.dto.AuthenticateResponse;
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

    private AuthenticateRequest req() {
        return new AuthenticateRequest();
    }

    private ResponseEntity<AuthenticateResponse> post(AuthenticateRequest req) {
        return rest.postForEntity("/ivr/authenticate", req, AuthenticateResponse.class);
    }

    @Test
    void testFullAuthFlow() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5551234567");
        start.setTargetLevel(AuthLevel.STANDARD);

        ResponseEntity<AuthenticateResponse> startResp = post(start);
        assertThat(startResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(startResp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(startResp.getBody().getNextRequiredToken()).isEqualTo(TokenType.ACCOUNT_NUMBER);

        String sessionId = startResp.getBody().getSessionId();
        assertThat(sessionId).isNotNull();

        AuthenticateRequest token = req();
        token.setSessionId(sessionId);
        token.setTokenType(TokenType.ACCOUNT_NUMBER);
        token.setTokenValue("123456789");

        ResponseEntity<AuthenticateResponse> tokenResp = post(token);
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(tokenResp.getBody().getNextRequiredToken()).isEqualTo(TokenType.PIN);

        token.setTokenType(TokenType.PIN);
        token.setTokenValue("1234");

        tokenResp = post(token);
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResp.getBody().getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(tokenResp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.STANDARD);
    }

    @Test
    void testBackupTokenFlow() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5557654321");
        start.setTargetLevel(AuthLevel.STANDARD);

        String sessionId = post(start).getBody().getSessionId();

        AuthenticateRequest token = req();
        token.setSessionId(sessionId);
        token.setTokenType(TokenType.ACCOUNT_NUMBER);
        token.setTokenValue("123456789");
        post(token);

        token.setTokenType(TokenType.SSN_LAST4);
        token.setTokenValue("1234");

        ResponseEntity<AuthenticateResponse> tokenResp = post(token);
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResp.getBody().getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(tokenResp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.STANDARD);
    }

    @Test
    void testFallbackPathFlow() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5551112222");
        start.setTargetLevel(AuthLevel.STANDARD);

        String sessionId = post(start).getBody().getSessionId();

        AuthenticateRequest token = req();
        token.setSessionId(sessionId);
        token.setTokenType(TokenType.ACCOUNT_NUMBER);
        token.setTokenValue("123456789");
        post(token);

        token.setTokenType(TokenType.PIN);
        token.setTokenValue("12");

        ResponseEntity<AuthenticateResponse> tokenResp = null;
        for (int i = 0; i < 3; i++) {
            tokenResp = post(token);
        }

        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(tokenResp.getBody().getNextRequiredToken()).isEqualTo(TokenType.OTP);

        token.setTokenType(TokenType.OTP);
        token.setTokenValue("123456");

        tokenResp = post(token);
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResp.getBody().getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(tokenResp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.STANDARD);
    }

    @Test
    void testGetStatus() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5553334444");
        start.setTargetLevel(AuthLevel.BASIC);

        String sessionId = post(start).getBody().getSessionId();

        ResponseEntity<AuthenticateResponse> statusResp = rest.getForEntity(
            "/ivr/authenticate/" + sessionId + "/status", AuthenticateResponse.class);
        assertThat(statusResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testDeleteSession() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5555555555");
        start.setTargetLevel(AuthLevel.BASIC);

        String sessionId = post(start).getBody().getSessionId();

        rest.delete("/ivr/authenticate/" + sessionId);

        ResponseEntity<AuthenticateResponse> statusResp = rest.getForEntity(
            "/ivr/authenticate/" + sessionId + "/status", AuthenticateResponse.class);
        assertThat(statusResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void testInitialTokens() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5556667777");
        start.setTargetLevel(AuthLevel.STANDARD);
        start.setInitialTokens(new java.util.HashMap<>());
        start.getInitialTokens().put(TokenType.ACCOUNT_NUMBER, "123456789");

        ResponseEntity<AuthenticateResponse> startResp = post(start);
        assertThat(startResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(startResp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(startResp.getBody().getNextRequiredToken()).isEqualTo(TokenType.PIN);
    }

    @Test
    void testUnknownBrand() {
        AuthenticateRequest start = req();
        start.setBrandId("UNKNOWN");
        start.setCallerId("5550000000");
        start.setTargetLevel(AuthLevel.BASIC);

        ResponseEntity<String> resp = rest.postForEntity(
            "/ivr/authenticate", start, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testTransferHappyPath() {
        AuthenticateRequest req = req();
        req.setSourceSystemId("LEGACY_IVR");
        req.setBrandId("BRAND_A");
        req.setCallerId("5551234567");
        req.setCurrentLevel(AuthLevel.BASIC);
        req.setTargetLevel(AuthLevel.STANDARD);
        req.setValidatedTokens(Arrays.asList(TokenType.ACCOUNT_NUMBER));

        ResponseEntity<AuthenticateResponse> resp = post(req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(resp.getBody().getNextRequiredToken()).isEqualTo(TokenType.PIN);
        assertThat(resp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.BASIC);
    }

    @Test
    void testTransferAlreadyAtTargetLevel() {
        AuthenticateRequest req = req();
        req.setSourceSystemId("LEGACY_IVR");
        req.setBrandId("BRAND_A");
        req.setCallerId("5552223333");
        req.setCurrentLevel(AuthLevel.NONE);
        req.setTargetLevel(AuthLevel.BASIC);
        req.setValidatedTokens(Arrays.asList(TokenType.ACCOUNT_NUMBER));

        ResponseEntity<AuthenticateResponse> resp = post(req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(resp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.BASIC);
    }

    @Test
    void testTransferTokenFiltering() {
        AuthenticateRequest req = req();
        req.setSourceSystemId("SALESFORCE");
        req.setBrandId("BRAND_A");
        req.setCallerId("5553334444");
        req.setCurrentLevel(AuthLevel.NONE);
        req.setTargetLevel(AuthLevel.STANDARD);
        req.setValidatedTokens(Arrays.asList(TokenType.ACCOUNT_NUMBER, TokenType.PIN));

        ResponseEntity<AuthenticateResponse> resp = post(req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getNextRequiredToken()).isEqualTo(TokenType.PIN);
    }

    @Test
    void testTransferLevelCapping() {
        AuthenticateRequest req = req();
        req.setSourceSystemId("SALESFORCE");
        req.setBrandId("BRAND_A");
        req.setCallerId("5554445555");
        req.setCurrentLevel(AuthLevel.STANDARD);
        req.setTargetLevel(AuthLevel.STANDARD);
        req.setValidatedTokens(Arrays.asList(TokenType.ACCOUNT_NUMBER));

        ResponseEntity<AuthenticateResponse> resp = post(req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.BASIC);
    }

    @Test
    void testTransferUnknownSource() {
        AuthenticateRequest req = req();
        req.setSourceSystemId("UNKNOWN_SYSTEM");
        req.setBrandId("BRAND_A");
        req.setCallerId("5555556666");
        req.setCurrentLevel(AuthLevel.NONE);
        req.setTargetLevel(AuthLevel.BASIC);
        req.setValidatedTokens(Arrays.asList(TokenType.ACCOUNT_NUMBER));

        ResponseEntity<String> resp = rest.postForEntity(
            "/ivr/authenticate", req, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
