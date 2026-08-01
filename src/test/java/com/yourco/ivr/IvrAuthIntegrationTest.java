package com.yourco.ivr;

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

    private static AuthenticateResponse body(ResponseEntity<AuthenticateResponse> response) {
        AuthenticateResponse b = response.getBody();
        assertThat(b).isNotNull();
        return b;
    }

    @Test
    void testFullAuthFlow() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5551234567");
        start.setTargetLevel(AuthLevel.STANDARD);

        ResponseEntity<AuthenticateResponse> startResp = post(start);
        assertThat(startResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(startResp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(startResp).getNextRequiredToken()).isEqualTo(TokenType.ACCOUNT_NUMBER);

        String sessionId = body(startResp).getSessionId();
        assertThat(sessionId).isNotNull();

        AuthenticateRequest token = req();
        token.setSessionId(sessionId);
        token.setTokenType(TokenType.ACCOUNT_NUMBER);
        token.setTokenValue("123456789");

        ResponseEntity<AuthenticateResponse> tokenResp = post(token);
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(tokenResp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(tokenResp).getNextRequiredToken()).isEqualTo(TokenType.PIN);

        token.setTokenType(TokenType.PIN);
        token.setTokenValue("1234");

        tokenResp = post(token);
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(tokenResp).getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(body(tokenResp).getCurrentLevel()).isEqualTo(AuthLevel.STANDARD);
    }

    /**
     * Regression: escalating an AUTHENTICATED session must collect the missing tokens for
     * the higher level, not short-circuit (the old guard treated AUTHENTICATED as terminal).
     * STANDARD (account + PIN) → escalate ELEVATED → must prompt for OTP → completes at ELEVATED.
     */
    @Test
    void testEscalationAfterAuthenticationPromptsForMissingTokens() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5551234567");
        start.setTargetLevel(AuthLevel.STANDARD);

        String sessionId = body(post(start)).getSessionId();

        AuthenticateRequest token = req();
        token.setSessionId(sessionId);
        token.setTokenType(TokenType.ACCOUNT_NUMBER);
        token.setTokenValue("123456789");
        post(token);

        token.setTokenType(TokenType.PIN);
        token.setTokenValue("1234");
        ResponseEntity<AuthenticateResponse> authResp = post(token);
        assertThat(body(authResp).getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(body(authResp).getCurrentLevel()).isEqualTo(AuthLevel.STANDARD);

        // Escalate to ELEVATED — ELEVATED path0 = [ACCOUNT_NUMBER, PIN, OTP]; OTP is missing.
        AuthenticateRequest escalate = req();
        escalate.setSessionId(sessionId);
        escalate.setTargetLevel(AuthLevel.ELEVATED);

        ResponseEntity<AuthenticateResponse> escalateResp = post(escalate);
        assertThat(escalateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(escalateResp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(escalateResp).getNextRequiredToken()).isEqualTo(TokenType.OTP);
        assertThat(body(escalateResp).getCurrentLevel()).isEqualTo(AuthLevel.STANDARD);

        // Provide OTP → AUTHENTICATED at ELEVATED.
        token.setTokenType(TokenType.OTP);
        token.setTokenValue("123456");
        ResponseEntity<AuthenticateResponse> finalResp = post(token);
        assertThat(finalResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(finalResp).getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(body(finalResp).getCurrentLevel()).isEqualTo(AuthLevel.ELEVATED);
    }

    /**
     * Escalation must verify caller ownership like token submission does — a request with a
     * mismatched callerId must be rejected (404) instead of re-opening the victim's session.
     */
    @Test
    void testEscalationWithWrongCallerIsRejected() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5551234567");
        start.setTargetLevel(AuthLevel.STANDARD);
        String sessionId = body(post(start)).getSessionId();

        AuthenticateRequest escalate = req();
        escalate.setSessionId(sessionId);
        escalate.setTargetLevel(AuthLevel.ELEVATED);
        escalate.setCallerId("9990000000"); // not the session's caller

        ResponseEntity<AuthenticateResponse> resp = post(escalate);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** A transfer without a targetLevel is a client error (400), not a 500. */
    @Test
    void testTransferWithoutTargetLevelIsRejected() {
        AuthenticateRequest req = req();
        req.setSourceSystemId("LEGACY_IVR");
        req.setBrandId("BRAND_A");
        req.setCallerId("5551234567");
        req.setCurrentLevel(AuthLevel.BASIC);
        req.setValidatedTokens(Arrays.asList(TokenType.ACCOUNT_NUMBER));
        // no targetLevel

        ResponseEntity<String> resp = rest.postForEntity(
            "/ivr/authenticate", req, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testBackupTokenFlow() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5557654321");
        start.setTargetLevel(AuthLevel.STANDARD);

        String sessionId = body(post(start)).getSessionId();

        AuthenticateRequest token = req();
        token.setSessionId(sessionId);
        token.setTokenType(TokenType.ACCOUNT_NUMBER);
        token.setTokenValue("123456789");
        post(token);

        token.setTokenType(TokenType.SSN_LAST4);
        token.setTokenValue("1234");

        ResponseEntity<AuthenticateResponse> tokenResp = post(token);
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(tokenResp).getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(body(tokenResp).getCurrentLevel()).isEqualTo(AuthLevel.STANDARD);
    }

    @Test
    void testFallbackPathFlow() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5551112222");
        start.setTargetLevel(AuthLevel.STANDARD);

        String sessionId = body(post(start)).getSessionId();

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
        assertThat(body(tokenResp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(tokenResp).getNextRequiredToken()).isEqualTo(TokenType.OTP);

        token.setTokenType(TokenType.OTP);
        token.setTokenValue("123456");

        tokenResp = post(token);
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(tokenResp).getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(body(tokenResp).getCurrentLevel()).isEqualTo(AuthLevel.STANDARD);
    }

    @Test
    void testGetStatus() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5553334444");
        start.setTargetLevel(AuthLevel.BASIC);

        String sessionId = body(post(start)).getSessionId();

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

        String sessionId = body(post(start)).getSessionId();

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
        assertThat(body(startResp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(startResp).getNextRequiredToken()).isEqualTo(TokenType.PIN);
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
        assertThat(body(resp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(resp).getNextRequiredToken()).isEqualTo(TokenType.PIN);
        assertThat(body(resp).getCurrentLevel()).isEqualTo(AuthLevel.BASIC);
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
        assertThat(body(resp).getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(body(resp).getCurrentLevel()).isEqualTo(AuthLevel.BASIC);
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
        assertThat(body(resp).getNextRequiredToken()).isEqualTo(TokenType.PIN);
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
        assertThat(body(resp).getCurrentLevel()).isEqualTo(AuthLevel.BASIC);
    }

    /**
     * Verifies that submitting a wrong token type counts as a failed attempt:
     * scenario A — a later-path token (OTP) submitted when PIN is expected (mid-path).
     */
    @Test
    void testWrongTokenTypeDecrementsRetryCount() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5551230001");
        start.setTargetLevel(AuthLevel.STANDARD);

        String sessionId = body(post(start)).getSessionId();

        // Advance past ACCOUNT_NUMBER
        AuthenticateRequest token = req();
        token.setSessionId(sessionId);
        token.setTokenType(TokenType.ACCOUNT_NUMBER);
        token.setTokenValue("123456789");
        ResponseEntity<AuthenticateResponse> resp = post(token);
        assertThat(body(resp).getNextRequiredToken()).isEqualTo(TokenType.PIN);
        assertThat(body(resp).getRemainingAttempts()).isEqualTo(3);

        // Submit OTP — not in acceptedTokens [PIN, SSN_LAST4, DATE_OF_BIRTH] for this step
        token.setTokenType(TokenType.OTP);
        token.setTokenValue("123456");
        resp = post(token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(resp).getNextRequiredToken()).isEqualTo(TokenType.PIN);
        assertThat(body(resp).getRemainingAttempts()).isEqualTo(2);
    }

    /**
     * Verifies that submitting a wrong token type counts as a failed attempt:
     * scenario B — a same-path token (PIN) submitted when ACCOUNT_NUMBER is expected
     * (first step, no prior tokens validated). Without the wrong-type guard, PIN would
     * pass the PinValidator and silently get added to validatedTokens, causing the system
     * to keep asking for ACCOUNT_NUMBER with remainingAttempts always at 3.
     */
    @Test
    void testWrongTokenTypeFirstStepDecrementsRetryCount() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5551230002");
        start.setTargetLevel(AuthLevel.STANDARD);

        String sessionId = body(post(start)).getSessionId();

        // First step expects ACCOUNT_NUMBER — submit PIN instead
        AuthenticateRequest token = req();
        token.setSessionId(sessionId);
        token.setTokenType(TokenType.PIN);
        token.setTokenValue("1234");  // valid PIN value — would pass the validator if not caught
        ResponseEntity<AuthenticateResponse> resp = post(token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        // Must still ask for ACCOUNT_NUMBER, not PIN
        assertThat(body(resp).getNextRequiredToken()).isEqualTo(TokenType.ACCOUNT_NUMBER);
        // Must decrement: 3 - 1 = 2 remaining
        assertThat(body(resp).getRemainingAttempts()).isEqualTo(2);
        // Must not have added PIN to validatedTokens (verify by checking accepted list)
        assertThat(body(resp).getAcceptedTokens()).containsExactly(TokenType.ACCOUNT_NUMBER);

        // Second wrong-type submission: count must continue decrementing
        resp = post(token);
        assertThat(body(resp).getRemainingAttempts()).isEqualTo(1);

        // Third wrong-type exhausts retries → redirect to agent immediately (no path switch).
        // Wrong-type submissions do not earn a fresh retry budget on an alternative path.
        resp = post(token);
        assertThat(body(resp).getStatus()).isEqualTo(SessionStatus.REDIRECT_TO_AGENT);
    }

    /**
     * Verifies that retry limits are tracked against the required-token slot, not the
     * individual submitted backup type. Failing PIN once, SSN_LAST4 once (a backup for
     * PIN), and DATE_OF_BIRTH once (another backup for PIN) should exhaust the 3-attempt
     * limit and trigger a path switch to the OTP path — even though no single token type
     * was failed 3 times individually.
     */
    @Test
    void testRetryLimitCountsAcrossBackupTokens() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5559991111");
        start.setTargetLevel(AuthLevel.STANDARD);

        String sessionId = body(post(start)).getSessionId();

        AuthenticateRequest token = req();
        token.setSessionId(sessionId);
        token.setTokenType(TokenType.ACCOUNT_NUMBER);
        token.setTokenValue("123456789");
        post(token); // valid — advances past ACCOUNT_NUMBER

        // Failure 1: PIN "12" is too short (fails PinValidator) → 1 failure on PIN slot
        token.setTokenType(TokenType.PIN);
        token.setTokenValue("12");
        ResponseEntity<AuthenticateResponse> resp = post(token);
        assertThat(body(resp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(resp).getNextRequiredToken()).isEqualTo(TokenType.PIN);
        assertThat(body(resp).getRemainingAttempts()).isEqualTo(2);

        // Failure 2: SSN_LAST4 "12" is too short (fails SsnLast4Validator)
        // → 2 failures on PIN slot (SSN_LAST4 is a backup for PIN)
        token.setTokenType(TokenType.SSN_LAST4);
        token.setTokenValue("12");
        resp = post(token);
        assertThat(body(resp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(resp).getNextRequiredToken()).isEqualTo(TokenType.PIN);
        assertThat(body(resp).getRemainingAttempts()).isEqualTo(1);

        // Failure 3: DATE_OF_BIRTH "bad-date" fails format validation
        // → 3 failures on PIN slot (DATE_OF_BIRTH is also a backup for PIN)
        // → retry limit exhausted → path switch to path1 → next token should be OTP
        token.setTokenType(TokenType.DATE_OF_BIRTH);
        token.setTokenValue("bad-date");
        resp = post(token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(resp).getNextRequiredToken()).isEqualTo(TokenType.OTP);
    }

    /**
     * Verifies that a failed backup-token submission returns the required-token type
     * (PIN) as nextRequiredToken — not the submitted backup type (SSN_LAST4). The
     * acceptedTokens list must also include the backup alternatives so the caller
     * always knows the full set of acceptable token types.
     */
    @Test
    void testBackupTokenFailureReturnsRequiredToken() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5558882222");
        start.setTargetLevel(AuthLevel.STANDARD);

        String sessionId = body(post(start)).getSessionId();

        AuthenticateRequest token = req();
        token.setSessionId(sessionId);
        token.setTokenType(TokenType.ACCOUNT_NUMBER);
        token.setTokenValue("123456789");
        post(token); // valid — advances past ACCOUNT_NUMBER

        // Submit SSN_LAST4 "12" — too short, fails SsnLast4Validator.
        // SSN_LAST4 is a backup for PIN on path0, so this is a failure against the PIN slot.
        token.setTokenType(TokenType.SSN_LAST4);
        token.setTokenValue("12");
        ResponseEntity<AuthenticateResponse> resp = post(token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        // Must ask for PIN (the required slot), not SSN_LAST4 (the submitted backup)
        assertThat(body(resp).getNextRequiredToken()).isEqualTo(TokenType.PIN);
        // Must expose backup alternatives so the caller can offer them to the customer
        assertThat(body(resp).getAcceptedTokens()).contains(TokenType.SSN_LAST4);
        assertThat(body(resp).getRemainingAttempts()).isEqualTo(2);
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

    // Repeated backup-token failures must count against the required slot and
    // eventually exhaust retries (not give infinite attempts).
    @Test
    void testBackupTokenRepeatedFailureExhaustsRetries() {
        // STANDARD path0 = [ACCOUNT_NUMBER, PIN], PIN backed by SSN_LAST4 / DATE_OF_BIRTH
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5550000099");
        start.setTargetLevel(AuthLevel.STANDARD);
        String sid = body(post(start)).getSessionId();

        // Advance past ACCOUNT_NUMBER slot
        AuthenticateRequest token = req();
        token.setSessionId(sid);
        token.setTokenType(TokenType.ACCOUNT_NUMBER);
        token.setTokenValue("123456789");
        post(token);

        // Fail SSN_LAST4 (backup for PIN) three times
        token.setTokenType(TokenType.SSN_LAST4);
        token.setTokenValue("12"); // too short, always fails

        ResponseEntity<AuthenticateResponse> r1 = post(token);
        assertThat(body(r1).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(r1).getNextRequiredToken()).isEqualTo(TokenType.PIN);
        assertThat(body(r1).getRemainingAttempts()).isEqualTo(2);

        ResponseEntity<AuthenticateResponse> r2 = post(token);
        assertThat(body(r2).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(r2).getNextRequiredToken()).isEqualTo(TokenType.PIN);
        assertThat(body(r2).getRemainingAttempts()).isEqualTo(1);

        // Third failure exhausts path0 PIN slot -> switch to path1 = [ACCOUNT_NUMBER, OTP]
        // ACCOUNT_NUMBER is already validated so path1 advances immediately to OTP
        ResponseEntity<AuthenticateResponse> r3 = post(token);
        assertThat(body(r3).getNextRequiredToken()).isEqualTo(TokenType.OTP);
        assertThat(body(r3).getRemainingAttempts()).isEqualTo(3);
    }

    /**
     * Regression test: submitting a DATE_OF_BIRTH token with a missing (null) value must be
     * handled as a graceful validation failure (200 / COLLECTING), not crash the format
     * validator with an NPE that surfaces as HTTP 500. DATE_OF_BIRTH is an accepted backup
     * for the PIN slot on BRAND_A STANDARD path0, so the value reaches DateOfBirthValidator.
     */
    @Test
    void testNullDateOfBirthValueIsRejectedGracefully() {
        AuthenticateRequest start = req();
        start.setBrandId("BRAND_A");
        start.setCallerId("5551239999");
        start.setTargetLevel(AuthLevel.STANDARD);

        String sessionId = body(post(start)).getSessionId();

        // Advance past ACCOUNT_NUMBER to the PIN slot (DATE_OF_BIRTH is an accepted backup)
        AuthenticateRequest token = req();
        token.setSessionId(sessionId);
        token.setTokenType(TokenType.ACCOUNT_NUMBER);
        token.setTokenValue("123456789");
        post(token);

        // Submit DATE_OF_BIRTH with no tokenValue (null) — must not 500
        token.setTokenType(TokenType.DATE_OF_BIRTH);
        token.setTokenValue(null);
        ResponseEntity<AuthenticateResponse> resp = post(token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(resp).getNextRequiredToken()).isEqualTo(TokenType.PIN);
        assertThat(body(resp).getRemainingAttempts()).isEqualTo(2);
    }
}
