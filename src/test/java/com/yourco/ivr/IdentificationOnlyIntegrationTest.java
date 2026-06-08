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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Integration tests for identification-only brands ({@code identificationOnly = true}).
 *
 * <p>These brands resolve the caller to a single party and stop — no authentication tokens are
 * collected and the result is {@code AUTHENTICATED} with {@code currentLevel = NONE}.
 *
 * <p>The {@code ID_ONLY_BRAND} fixture lives in {@code config/brands/id_only_brand.json}. The
 * {@link PartyLookupProvider} is mocked so we can exercise both the single-party and
 * multiple-party (disambiguation) resolution paths.
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

    private Party party(String id, String account) {
        Party p = new Party();
        p.setPartyId(id);
        p.setAccountNumber(account);
        p.setActive(true);
        p.setPrimaryAni(true);
        return p;
    }

    @Test
    void singlePartyResolvesImmediatelyToNone() {
        when(partyLookup.lookupByAni("1110001111"))
            .thenReturn(Collections.singletonList(party("P-001", "111000")));

        AuthenticateRequest start = new AuthenticateRequest();
        start.setBrandId("ID_ONLY_BRAND");
        start.setCallerId("1110001111");
        start.setTargetLevel(AuthLevel.NONE); // ignored in identification-only mode

        ResponseEntity<AuthenticateResponse> resp = post(start);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(resp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.NONE);
        assertThat(resp.getBody().getMatchedPartyId()).isEqualTo("P-001");
        assertThat(resp.getBody().getNextRequiredToken()).isNull();
    }

    @Test
    void multiplePartiesDisambiguateThenResolveToNone() {
        when(partyLookup.lookupByAni("2220002222")).thenReturn(Arrays.asList(
            party("P-100", "100100"),
            party("P-200", "200200"),
            party("P-300", "300300")));

        AuthenticateRequest start = new AuthenticateRequest();
        start.setBrandId("ID_ONLY_BRAND");
        start.setCallerId("2220002222");
        start.setTargetLevel(AuthLevel.NONE);

        ResponseEntity<AuthenticateResponse> startResp = post(start);
        assertThat(startResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Still resolving: must prompt for a disambiguating token, not yet authenticated.
        assertThat(startResp.getBody().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(startResp.getBody().getNextRequiredToken()).isEqualTo(TokenType.ACCOUNT_NUMBER);

        String sessionId = startResp.getBody().getSessionId();

        AuthenticateRequest token = new AuthenticateRequest();
        token.setSessionId(sessionId);
        token.setTokenType(TokenType.ACCOUNT_NUMBER);
        token.setTokenValue("200200"); // uniquely matches P-200

        ResponseEntity<AuthenticateResponse> tokenResp = post(token);
        assertThat(tokenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResp.getBody().getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(tokenResp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.NONE);
        assertThat(tokenResp.getBody().getMatchedPartyId()).isEqualTo("P-200");
    }

    @Test
    void escalateIsRejectedForIdentificationOnlyBrand() {
        when(partyLookup.lookupByAni("3330003333"))
            .thenReturn(Collections.singletonList(party("P-900", "999000")));

        AuthenticateRequest start = new AuthenticateRequest();
        start.setBrandId("ID_ONLY_BRAND");
        start.setCallerId("3330003333");
        start.setTargetLevel(AuthLevel.NONE);
        String sessionId = post(start).getBody().getSessionId();

        AuthenticateRequest escalate = new AuthenticateRequest();
        escalate.setSessionId(sessionId);
        escalate.setTargetLevel(AuthLevel.STANDARD);

        ResponseEntity<String> resp = rest.postForEntity(
            "/ivr/authenticate", escalate, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void validationAcceptsIdentificationOnlyBrandWithoutLevelRules() {
        BrandAuthConfig config = new BrandAuthConfig();
        config.setBrandId("ID_ONLY_VALIDATION");
        config.setIdentificationOnly(true);
        // no levelRules set

        assertThat(brandService.validate(config).isValid()).isTrue();
    }
}
