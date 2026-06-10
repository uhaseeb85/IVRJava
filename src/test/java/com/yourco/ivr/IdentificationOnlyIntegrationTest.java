package com.yourco.ivr;

import com.yourco.ivr.api.dto.AuthenticateRequest;
import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.Party;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.partylookup.PartyLookupProvider;
import com.yourco.ivr.service.BrandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Integration tests for identification-only brands ({@code identificationOnly = true}).
 *
 * <p>When {@code levelRules[NONE]} is defined, identification tokens are collected and
 * matched against the resolved party's fields to confirm caller identity. Without
 * {@code levelRules}, the session finalizes immediately after party resolution
 * (backward-compatible behavior).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentificationOnlyIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private BrandService brandService;

    @MockBean
    private PartyLookupProvider partyLookup;

    private ResponseEntity<AuthenticateResponse> post(AuthenticateRequest req) {
        return rest.postForEntity("/ivr/authenticate", req, AuthenticateResponse.class);
    }

    private static AuthenticateResponse body(ResponseEntity<AuthenticateResponse> response) {
        AuthenticateResponse b = response.getBody();
        assertThat(b).isNotNull();
        return b;
    }

    private Party party(String id, String account) {
        Party p = new Party();
        p.setPartyId(id);
        p.setAccountNumber(account);
        p.setDateOfBirth("1990-01-15");
        p.setSsnLast4("1234");
        p.setCardLast4("5678");
        p.setActive(true);
        p.setPrimaryAni(true);
        return p;
    }

    @Test
    void collectsIdentificationTokensThenResolves() {
        when(partyLookup.lookupByAni("1110001111"))
            .thenReturn(Collections.singletonList(party("P-001", "111000")));

        AuthenticateRequest start = new AuthenticateRequest();
        start.setBrandId("ID_ONLY_BRAND");
        start.setCallerId("1110001111");
        start.setTargetLevel(AuthLevel.NONE);

        ResponseEntity<AuthenticateResponse> resp = post(start);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(resp).getNextRequiredToken()).isEqualTo(TokenType.ACCOUNT_NUMBER);

        String sessionId = body(resp).getSessionId();

        AuthenticateRequest token1 = new AuthenticateRequest();
        token1.setSessionId(sessionId);
        token1.setTokenType(TokenType.ACCOUNT_NUMBER);
        token1.setTokenValue("111000");

        ResponseEntity<AuthenticateResponse> t1Resp = post(token1);
        assertThat(t1Resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(t1Resp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(t1Resp).getNextRequiredToken()).isEqualTo(TokenType.PIN);

        AuthenticateRequest token2 = new AuthenticateRequest();
        token2.setSessionId(sessionId);
        token2.setTokenType(TokenType.PIN);
        token2.setTokenValue("1234");

        ResponseEntity<AuthenticateResponse> t2Resp = post(token2);
        assertThat(t2Resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(t2Resp).getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(body(t2Resp).getCurrentLevel()).isEqualTo(AuthLevel.NONE);
        assertThat(body(t2Resp).getMatchedPartyId()).isEqualTo("P-001");
    }

    @Test
    void identificationTokenMismatchFails() {
        when(partyLookup.lookupByAni("4440004444"))
            .thenReturn(Collections.singletonList(party("P-400", "ABC123")));

        AuthenticateRequest start = new AuthenticateRequest();
        start.setBrandId("ID_ONLY_BRAND");
        start.setCallerId("4440004444");
        start.setTargetLevel(AuthLevel.NONE);

        String sessionId = body(post(start)).getSessionId();

        AuthenticateRequest token1 = new AuthenticateRequest();
        token1.setSessionId(sessionId);
        token1.setTokenType(TokenType.ACCOUNT_NUMBER);
        token1.setTokenValue("WRONG");

        ResponseEntity<AuthenticateResponse> t1Resp = post(token1);
        assertThat(t1Resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(t1Resp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(t1Resp).getRemainingAttempts()).isEqualTo(2);

        AuthenticateRequest token2 = new AuthenticateRequest();
        token2.setSessionId(sessionId);
        token2.setTokenType(TokenType.ACCOUNT_NUMBER);
        token2.setTokenValue("WRONG2");

        ResponseEntity<AuthenticateResponse> t2Resp = post(token2);
        assertThat(body(t2Resp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(t2Resp).getRemainingAttempts()).isEqualTo(1);

        AuthenticateRequest token3 = new AuthenticateRequest();
        token3.setSessionId(sessionId);
        token3.setTokenType(TokenType.ACCOUNT_NUMBER);
        token3.setTokenValue("WRONG3");

        ResponseEntity<AuthenticateResponse> t3Resp = post(token3);
        assertThat(body(t3Resp).getStatus()).isEqualTo(SessionStatus.REDIRECT_TO_AGENT);
    }

    @Test
    void identificationTokensWithBackup() {
        when(partyLookup.lookupByAni("5550005555"))
            .thenReturn(Collections.singletonList(party("P-500", "555000")));

        AuthenticateRequest start = new AuthenticateRequest();
        start.setBrandId("ID_ONLY_BRAND");
        start.setCallerId("5550005555");
        start.setTargetLevel(AuthLevel.NONE);

        String sessionId = body(post(start)).getSessionId();

        AuthenticateRequest token1 = new AuthenticateRequest();
        token1.setSessionId(sessionId);
        token1.setTokenType(TokenType.ACCOUNT_NUMBER);
        token1.setTokenValue("555000");

        ResponseEntity<AuthenticateResponse> t1Resp = post(token1);
        assertThat(body(t1Resp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(t1Resp).getNextRequiredToken()).isEqualTo(TokenType.PIN);

        AuthenticateRequest token2 = new AuthenticateRequest();
        token2.setSessionId(sessionId);
        token2.setTokenType(TokenType.SSN_LAST4);
        token2.setTokenValue("1234");

        ResponseEntity<AuthenticateResponse> t2Resp = post(token2);
        assertThat(t2Resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(t2Resp).getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(body(t2Resp).getCurrentLevel()).isEqualTo(AuthLevel.NONE);
    }

    @Test
    void identificationTokensAfterDisambiguation() {
        Party p1 = party("P-100", "100100");
        Party p2 = party("P-200", "200200");
        Party p3 = party("P-300", "300300");
        when(partyLookup.lookupByAni("2220002222")).thenReturn(Arrays.asList(p1, p2, p3));

        AuthenticateRequest start = new AuthenticateRequest();
        start.setBrandId("ID_ONLY_BRAND");
        start.setCallerId("2220002222");
        start.setTargetLevel(AuthLevel.NONE);

        ResponseEntity<AuthenticateResponse> startResp = post(start);
        assertThat(startResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(startResp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(startResp).getNextRequiredToken()).isEqualTo(TokenType.ACCOUNT_NUMBER);

        String sessionId = body(startResp).getSessionId();

        AuthenticateRequest disToken = new AuthenticateRequest();
        disToken.setSessionId(sessionId);
        disToken.setTokenType(TokenType.ACCOUNT_NUMBER);
        disToken.setTokenValue("200200");

        ResponseEntity<AuthenticateResponse> disResp = post(disToken);
        assertThat(disResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(disResp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(disResp).getNextRequiredToken()).isEqualTo(TokenType.PIN);

        AuthenticateRequest pinToken = new AuthenticateRequest();
        pinToken.setSessionId(sessionId);
        pinToken.setTokenType(TokenType.PIN);
        pinToken.setTokenValue("1234");

        ResponseEntity<AuthenticateResponse> pinResp = post(pinToken);
        assertThat(pinResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(pinResp).getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(body(pinResp).getCurrentLevel()).isEqualTo(AuthLevel.NONE);
        assertThat(body(pinResp).getMatchedPartyId()).isEqualTo("P-200");
    }

    @Test
    void identificationTokensWithInitialTokens() {
        when(partyLookup.lookupByAni("6660006666"))
            .thenReturn(Collections.singletonList(party("P-600", "666000")));

        Map<TokenType, String> initialTokens = new LinkedHashMap<>();
        initialTokens.put(TokenType.ACCOUNT_NUMBER, "666000");

        AuthenticateRequest start = new AuthenticateRequest();
        start.setBrandId("ID_ONLY_BRAND");
        start.setCallerId("6660006666");
        start.setTargetLevel(AuthLevel.NONE);
        start.setInitialTokens(initialTokens);

        ResponseEntity<AuthenticateResponse> resp = post(start);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(body(resp).getNextRequiredToken()).isEqualTo(TokenType.PIN);
    }

    @Test
    void escalateIsRejectedForIdentificationOnlyBrand() {
        when(partyLookup.lookupByAni("3330003333"))
            .thenReturn(Collections.singletonList(party("P-900", "999000")));

        AuthenticateRequest start = new AuthenticateRequest();
        start.setBrandId("ID_ONLY_BRAND");
        start.setCallerId("3330003333");
        start.setTargetLevel(AuthLevel.NONE);

        String sessionId = body(post(start)).getSessionId();

        // Complete identification first
        AuthenticateRequest token1 = new AuthenticateRequest();
        token1.setSessionId(sessionId);
        token1.setTokenType(TokenType.ACCOUNT_NUMBER);
        token1.setTokenValue("999000");
        post(token1);

        AuthenticateRequest token2 = new AuthenticateRequest();
        token2.setSessionId(sessionId);
        token2.setTokenType(TokenType.PIN);
        token2.setTokenValue("1234");
        post(token2);

        AuthenticateRequest escalate = new AuthenticateRequest();
        escalate.setSessionId(sessionId);
        escalate.setTargetLevel(AuthLevel.STANDARD);

        ResponseEntity<String> resp = rest.postForEntity(
            "/ivr/authenticate", escalate, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void noLevelRulesStillWorks() {
        when(partyLookup.lookupByAni("7770007777"))
            .thenReturn(Collections.singletonList(party("P-700", "777000")));

        AuthenticateRequest start = new AuthenticateRequest();
        start.setBrandId("ID_ONLY_NO_RULES");
        start.setCallerId("7770007777");
        start.setTargetLevel(AuthLevel.NONE);

        ResponseEntity<AuthenticateResponse> resp = post(start);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(body(resp).getCurrentLevel()).isEqualTo(AuthLevel.NONE);
        assertThat(body(resp).getMatchedPartyId()).isEqualTo("P-700");
        assertThat(body(resp).getNextRequiredToken()).isNull();
    }

    @Test
    void validationAcceptsIdentificationOnlyBrandWithoutLevelRules() {
        BrandAuthConfig config = new BrandAuthConfig();
        config.setBrandId("ID_ONLY_VALIDATION");
        config.setIdentificationOnly(true);

        assertThat(brandService.validate(config).isValid()).isTrue();
    }
}
