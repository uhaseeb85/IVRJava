package com.yourco.ivr;

import com.yourco.ivr.api.dto.AuthenticateRequest;
import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.domain.*;
import com.yourco.ivr.partylookup.PartyLookupProvider;
import com.yourco.ivr.preference.CustomerPreferenceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DisambiguationAndPreferenceTest {

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private PartyLookupProvider partyLookup;

    @MockBean
    private CustomerPreferenceProvider preferenceProvider;

    private Party createParty(String partyId, String accountNumber, String dob,
                               String ssnLast4, boolean active, boolean primary) {
        Party p = new Party();
        p.setPartyId(partyId);
        p.setAccountNumber(accountNumber);
        p.setDateOfBirth(dob);
        p.setSsnLast4(ssnLast4);
        p.setActive(active);
        p.setPrimaryAni(primary);
        return p;
    }

    private AuthenticateRequest req() {
        return new AuthenticateRequest();
    }

    private ResponseEntity<AuthenticateResponse> post(AuthenticateRequest req) {
        return rest.postForEntity("/ivr/authenticate", req, AuthenticateResponse.class);
    }

    private AuthenticateRequest startReq(String brandId, String callerId, AuthLevel target) {
        AuthenticateRequest r = req();
        r.setBrandId(brandId);
        r.setCallerId(callerId);
        r.setTargetLevel(target);
        return r;
    }

    private AuthenticateRequest tokenReq(String sessionId, TokenType type, String value) {
        AuthenticateRequest r = req();
        r.setSessionId(sessionId);
        r.setTokenType(type);
        r.setTokenValue(value);
        return r;
    }

    // ── Disambiguation Tests ─────────────────────────────────────────────────

    @Test
    void testDisambiguationSingleParty() {
        Party p1 = createParty("P1", "111111", "1985-03-15", "1234", true, true);
        when(partyLookup.lookupByAni("5551111111")).thenReturn(Collections.singletonList(p1));
        when(preferenceProvider.getPreferences(anyString(), anyString()))
            .thenReturn(new CustomerPreference());

        ResponseEntity<AuthenticateResponse> resp = post(startReq("TEST_BRAND", "5551111111", AuthLevel.BASIC));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getPhase()).isEqualTo(SessionPhase.AUTHENTICATING);
        assertThat(resp.getBody().getMatchedPartyId()).isEqualTo("P1");
        assertThat(resp.getBody().getNextRequiredToken()).isEqualTo(TokenType.ACCOUNT_NUMBER);
    }

    @Test
    void testDisambiguationMultiParty() {
        Party p1 = createParty("P1", "111111", "1985-03-15", "1234", true, true);
        Party p2 = createParty("P2", "222222", "1990-07-22", "5678", true, true);
        when(partyLookup.lookupByAni("5552222222")).thenReturn(Arrays.asList(p1, p2));
        when(preferenceProvider.getPreferences(anyString(), anyString()))
            .thenReturn(new CustomerPreference());

        ResponseEntity<AuthenticateResponse> startResp = post(startReq("TEST_BRAND", "5552222222", AuthLevel.BASIC));

        assertThat(startResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(startResp.getBody().getPhase()).isEqualTo(SessionPhase.DISAMBIGUATION);
        assertThat(startResp.getBody().getNextRequiredToken()).isNotNull();
        TokenType disambigToken = startResp.getBody().getNextRequiredToken();

        String sessionId = startResp.getBody().getSessionId();

        String value = disambigToken == TokenType.ACCOUNT_NUMBER ? "111111"
            : disambigToken == TokenType.SSN_LAST4 ? "1234"
            : disambigToken == TokenType.DATE_OF_BIRTH ? "1985-03-15" : "";

        ResponseEntity<AuthenticateResponse> tokenResp = post(tokenReq(sessionId, disambigToken, value));
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResp.getBody().getPhase()).isEqualTo(SessionPhase.AUTHENTICATING);
        assertThat(tokenResp.getBody().getMatchedPartyId()).isEqualTo("P1");
    }

    @Test
    void testDisambiguationRulesNarrowToSingle() {
        Party p1 = createParty("P1", "111111", "1985-03-15", "1234", true, true);
        Party p2 = createParty("P2", "222222", "1990-07-22", "5678", false, false);
        when(partyLookup.lookupByAni("5553333333")).thenReturn(Arrays.asList(p1, p2));
        when(preferenceProvider.getPreferences(anyString(), anyString()))
            .thenReturn(new CustomerPreference());

        ResponseEntity<AuthenticateResponse> resp = post(startReq("TEST_BRAND", "5553333333", AuthLevel.BASIC));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getPhase()).isEqualTo(SessionPhase.AUTHENTICATING);
        assertThat(resp.getBody().getMatchedPartyId()).isEqualTo("P1");
    }

    @Test
    void testDisambiguationZeroParties() {
        when(partyLookup.lookupByAni("5550000000")).thenReturn(Collections.emptyList());

        ResponseEntity<AuthenticateResponse> resp = post(startReq("TEST_BRAND", "5550000000", AuthLevel.BASIC));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testDisambiguationMaxRoundsExceeded() {
        Party p1 = createParty("P1", "111111", "1985-03-15", "1234", true, true);
        Party p2 = createParty("P2", "111111", "1985-03-15", "1234", true, true);
        Party p3 = createParty("P3", "111111", "1985-03-15", "1234", true, true);
        when(partyLookup.lookupByAni("5554444444")).thenReturn(Arrays.asList(p1, p2, p3));
        when(preferenceProvider.getPreferences(anyString(), anyString()))
            .thenReturn(new CustomerPreference());

        ResponseEntity<AuthenticateResponse> startResp = post(startReq("TEST_BRAND", "5554444444", AuthLevel.BASIC));

        assertThat(startResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(startResp.getBody().getPhase()).isEqualTo(SessionPhase.DISAMBIGUATION);

        String sessionId = startResp.getBody().getSessionId();
        TokenType token = startResp.getBody().getNextRequiredToken();

        post(tokenReq(sessionId, token, "111111"));
        ResponseEntity<AuthenticateResponse> resp = post(tokenReq(sessionId, token, "111111"));

        assertThat(resp.getBody().getStatus()).isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void testDefaultDisambiguationSingleParty() {
        Party p1 = createParty("P1", "111111", "1985-03-15", "1234", true, true);
        when(partyLookup.lookupByAni("5555555555")).thenReturn(Collections.singletonList(p1));
        when(preferenceProvider.getPreferences(anyString(), anyString()))
            .thenReturn(new CustomerPreference());

        ResponseEntity<AuthenticateResponse> resp = post(startReq("BRAND_A", "5555555555", AuthLevel.BASIC));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getPhase()).isEqualTo(SessionPhase.AUTHENTICATING);
        assertThat(resp.getBody().getMatchedPartyId()).isEqualTo("P1");
        assertThat(resp.getBody().getNextRequiredToken()).isEqualTo(TokenType.ACCOUNT_NUMBER);
    }

    @Test
    void testPhaseTransition() {
        Party p1 = createParty("P1", "111111", "1985-03-15", "1234", true, true);
        Party p2 = createParty("P2", "222222", "1990-07-22", "5678", true, true);
        when(partyLookup.lookupByAni("5556666666")).thenReturn(Arrays.asList(p1, p2));
        when(preferenceProvider.getPreferences(anyString(), anyString()))
            .thenReturn(new CustomerPreference());

        ResponseEntity<AuthenticateResponse> startResp = post(startReq("TEST_BRAND", "5556666666", AuthLevel.BASIC));

        assertThat(startResp.getBody().getPhase()).isEqualTo(SessionPhase.DISAMBIGUATION);
        String sessionId = startResp.getBody().getSessionId();

        TokenType token = startResp.getBody().getNextRequiredToken();
        String value = token == TokenType.ACCOUNT_NUMBER ? "111111" : "1234";
        ResponseEntity<AuthenticateResponse> tokenResp = post(tokenReq(sessionId, token, value));

        assertThat(tokenResp.getBody().getPhase()).isEqualTo(SessionPhase.AUTHENTICATING);
        assertThat(tokenResp.getBody().getMatchedPartyId()).isEqualTo("P1");
    }

    @Test
    void testDisambiguationTokenNotReusedInAuth() {
        Party p1 = createParty("P1", "111111", "1985-03-15", "1234", true, true);
        Party p2 = createParty("P2", "222222", "1990-07-22", "5678", true, true);
        when(partyLookup.lookupByAni("5557777777")).thenReturn(Arrays.asList(p1, p2));
        when(preferenceProvider.getPreferences(anyString(), anyString()))
            .thenReturn(new CustomerPreference());

        ResponseEntity<AuthenticateResponse> startResp = post(startReq("TEST_BRAND", "5557777777", AuthLevel.BASIC));

        String sessionId = startResp.getBody().getSessionId();
        TokenType disambigToken = startResp.getBody().getNextRequiredToken();

        String value = disambigToken == TokenType.ACCOUNT_NUMBER ? "111111"
            : disambigToken == TokenType.SSN_LAST4 ? "1234"
            : "1985-03-15";
        ResponseEntity<AuthenticateResponse> resolvedResp = post(tokenReq(sessionId, disambigToken, value));

        assertThat(resolvedResp.getBody().getPhase()).isEqualTo(SessionPhase.AUTHENTICATING);
        assertThat(resolvedResp.getBody().getNextRequiredToken()).isEqualTo(TokenType.ACCOUNT_NUMBER);
    }

    // ── Customer Preference Tests ─────────────────────────────────────────────

    @Test
    void testPreferenceBlocksPin() {
        Party p1 = createParty("P1", "111111", "1985-03-15", "1234", true, true);
        when(partyLookup.lookupByAni("5558888888")).thenReturn(Collections.singletonList(p1));

        CustomerPreference prefs = new CustomerPreference();
        prefs.setBlockedTokens(EnumSet.of(TokenType.PIN));
        when(preferenceProvider.getPreferences(anyString(), anyString())).thenReturn(prefs);

        ResponseEntity<AuthenticateResponse> startResp = post(startReq("TEST_BRAND", "5558888888", AuthLevel.STANDARD));

        assertThat(startResp.getBody().getPhase()).isEqualTo(SessionPhase.AUTHENTICATING);
        String sessionId = startResp.getBody().getSessionId();

        ResponseEntity<AuthenticateResponse> tokenResp = post(tokenReq(sessionId, TokenType.ACCOUNT_NUMBER, "111111"));

        assertThat(tokenResp.getBody().getPhase()).isEqualTo(SessionPhase.AUTHENTICATING);
        assertThat(tokenResp.getBody().getNextRequiredToken()).isIn(TokenType.SSN_LAST4, TokenType.DATE_OF_BIRTH);
    }

    @Test
    void testPreferenceBlocksAllInPath() {
        Party p1 = createParty("P1", "111111", "1985-03-15", "1234", true, true);
        when(partyLookup.lookupByAni("5559999999")).thenReturn(Collections.singletonList(p1));

        CustomerPreference prefs = new CustomerPreference();
        prefs.setBlockedTokens(EnumSet.of(TokenType.PIN, TokenType.SSN_LAST4, TokenType.DATE_OF_BIRTH));
        when(preferenceProvider.getPreferences(anyString(), anyString())).thenReturn(prefs);

        ResponseEntity<AuthenticateResponse> startResp = post(startReq("TEST_BRAND", "5559999999", AuthLevel.STANDARD));

        String sessionId = startResp.getBody().getSessionId();

        ResponseEntity<AuthenticateResponse> tokenResp = post(tokenReq(sessionId, TokenType.ACCOUNT_NUMBER, "111111"));

        assertThat(tokenResp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(tokenResp.getBody().getNextRequiredToken()).isEqualTo(TokenType.OTP);
    }

    @Test
    void testPartyPersistence() {
        Party p1 = createParty("P1", "111111", "1985-03-15", "1234", true, true);
        when(partyLookup.lookupByAni("5551212121")).thenReturn(Collections.singletonList(p1));
        when(preferenceProvider.getPreferences(anyString(), anyString()))
            .thenReturn(new CustomerPreference());

        ResponseEntity<AuthenticateResponse> startResp = post(startReq("TEST_BRAND", "5551212121", AuthLevel.BASIC));

        String sessionId = startResp.getBody().getSessionId();

        ResponseEntity<AuthenticateResponse> statusResp = rest.getForEntity(
            "/ivr/authenticate/" + sessionId + "/status", AuthenticateResponse.class);

        assertThat(statusResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResp.getBody().getMatchedPartyId()).isEqualTo("P1");
        assertThat(statusResp.getBody().getPhase()).isEqualTo(SessionPhase.AUTHENTICATING);
    }

    @Test
    void testPreferencePersistence() {
        Party p1 = createParty("P1", "222222", "1990-01-01", "9999", true, true);
        when(partyLookup.lookupByAni("5551313131")).thenReturn(Collections.singletonList(p1));

        CustomerPreference prefs = new CustomerPreference();
        prefs.setBlockedTokens(EnumSet.of(TokenType.PIN));
        when(preferenceProvider.getPreferences(anyString(), anyString())).thenReturn(prefs);

        ResponseEntity<AuthenticateResponse> startResp = post(startReq("TEST_BRAND", "5551313131", AuthLevel.BASIC));

        String sessionId = startResp.getBody().getSessionId();

        ResponseEntity<AuthenticateResponse> statusResp = rest.getForEntity(
            "/ivr/authenticate/" + sessionId + "/status", AuthenticateResponse.class);

        assertThat(statusResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResp.getBody().getPhase()).isEqualTo(SessionPhase.AUTHENTICATING);
    }
}
