package com.yourco.ivr;

import com.yourco.ivr.api.dto.AuthenticateRequest;
import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.api.dto.ProcessingEvent;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.LevelRule;
import com.yourco.ivr.domain.config.TokenPath;
import com.yourco.ivr.partylookup.StubPartyLookupProvider;
import com.yourco.ivr.registry.BrandRulesRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for declarative level determination ({@code levelDetermination}
 * brand config). Uses DEMO_BRAND (config/brands/determination_demo.json):
 * PARTY_ATTRIBUTE segment=PREMIUM → ELEVATED, PARTY_ACTIVE → STANDARD, default BASIC.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LevelDeterminationIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private BrandRulesRegistry rulesRegistry;

    /**
     * Registers an in-memory brand with NO {@code levelDetermination} section — the
     * legacy path that requires a caller-supplied target level on start.
     */
    private void registerLegacyBrand() {
        TokenPath path = new TokenPath();
        path.setPathIndex(0);
        path.setRequiredTokens(Collections.singletonList(TokenType.ACCOUNT_NUMBER));
        LevelRule rule = new LevelRule();
        rule.setPaths(Collections.singletonList(path));
        rule.setMaxRetriesPerToken(3);
        Map<AuthLevel, LevelRule> levels = new HashMap<>();
        levels.put(AuthLevel.BASIC, rule);
        BrandAuthConfig config = new BrandAuthConfig();
        config.setBrandId("LEGACY_BRAND");
        config.setLevelRules(levels);
        rulesRegistry.register(config);
    }

    private ResponseEntity<AuthenticateResponse> post(AuthenticateRequest req) {
        return rest.postForEntity("/ivr/authenticate", req, AuthenticateResponse.class);
    }

    private static AuthenticateResponse body(ResponseEntity<AuthenticateResponse> response) {
        AuthenticateResponse b = response.getBody();
        assertThat(b).isNotNull();
        return b;
    }

    private static AuthenticateRequest start(String brandId, String callerId) {
        AuthenticateRequest start = new AuthenticateRequest();
        start.setBrandId(brandId);
        start.setCallerId(callerId);
        return start;
    }

    private static AuthenticateRequest token(String sessionId, TokenType type, String value) {
        AuthenticateRequest token = new AuthenticateRequest();
        token.setSessionId(sessionId);
        token.setTokenType(type);
        token.setTokenValue(value);
        return token;
    }

    @Test
    void firstMatchingRuleSelectsElevatedLevelForPremiumParty() {
        ResponseEntity<AuthenticateResponse> resp =
            post(start("DEMO_BRAND", StubPartyLookupProvider.PREMIUM_ANI));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getTargetLevel()).isEqualTo(AuthLevel.ELEVATED);
        assertThat(logMessages(body(resp)))
            .anyMatch(m -> m.contains("Level determined: ELEVATED"));
    }

    @Test
    void laterRuleSelectsStandardLevelForRegularParty() {
        ResponseEntity<AuthenticateResponse> resp =
            post(start("DEMO_BRAND", "5551234567"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getTargetLevel()).isEqualTo(AuthLevel.STANDARD);
        assertThat(logMessages(body(resp)))
            .anyMatch(m -> m.contains("Level determined: STANDARD"));
    }

    @Test
    void defaultLevelUsedWhenNoRuleMatches() {
        ResponseEntity<AuthenticateResponse> resp =
            post(start("DEMO_BRAND", StubPartyLookupProvider.INACTIVE_ANI));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getTargetLevel()).isEqualTo(AuthLevel.BASIC);
        assertThat(logMessages(body(resp)))
            .anyMatch(m -> m.contains("Level determined: BASIC") && m.contains("default"));
    }

    @Test
    void derivedLevelOverridesCallerSuppliedTargetLevel() {
        AuthenticateRequest req = start("DEMO_BRAND", StubPartyLookupProvider.PREMIUM_ANI);
        req.setTargetLevel(AuthLevel.BASIC); // caller asks for less — system still derives ELEVATED

        ResponseEntity<AuthenticateResponse> resp = post(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getTargetLevel()).isEqualTo(AuthLevel.ELEVATED);
    }

    @Test
    void startWithoutTargetLevelIsAccepted() {
        ResponseEntity<AuthenticateResponse> resp =
            post(start("DEMO_BRAND", "5551234567"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getTargetLevel()).isEqualTo(AuthLevel.STANDARD);
    }

    @Test
    void legacyBrandWithoutTargetLevelIsRejected() {
        // A brand without a levelDetermination section still requires a caller-supplied
        // target level (legacy behavior), so omitting it is a 400.
        registerLegacyBrand();

        ResponseEntity<AuthenticateResponse> resp =
            post(start("LEGACY_BRAND", "5551234567"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void brandAWithoutTargetLevelDerivesStandard() {
        // Regression for the reported UI error: the admin console sends no targetLevel,
        // so BRAND_A (which now defines levelDetermination) must derive it — STANDARD for
        // a regular active caller.
        ResponseEntity<AuthenticateResponse> resp =
            post(start("BRAND_A", "5551234567"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getTargetLevel()).isEqualTo(AuthLevel.STANDARD);
        assertThat(logMessages(body(resp)))
            .anyMatch(m -> m.contains("Level determined: STANDARD"));
    }

    @Test
    void legacyBrandStillHonorsCallerSuppliedTargetLevel() {
        // A brand without a levelDetermination section uses the caller-supplied target
        // level as-is (legacy behavior).
        registerLegacyBrand();
        AuthenticateRequest req = start("LEGACY_BRAND", "5551234567");
        req.setTargetLevel(AuthLevel.BASIC);

        ResponseEntity<AuthenticateResponse> resp = post(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp).getTargetLevel()).isEqualTo(AuthLevel.BASIC);
    }

    @Test
    void derivedLevelDrivesTheFullAuthenticationPath() {
        // Premium caller → system requires ELEVATED (account + PIN + OTP on path 0)
        ResponseEntity<AuthenticateResponse> startResp =
            post(start("DEMO_BRAND", StubPartyLookupProvider.PREMIUM_ANI));
        String sessionId = body(startResp).getSessionId();
        assertThat(body(startResp).getTargetLevel()).isEqualTo(AuthLevel.ELEVATED);

        ResponseEntity<AuthenticateResponse> accountResp =
            post(token(sessionId, TokenType.ACCOUNT_NUMBER, StubPartyLookupProvider.PREMIUM_ANI));
        assertThat(body(accountResp).getNextRequiredToken()).isEqualTo(TokenType.PIN);

        ResponseEntity<AuthenticateResponse> pinResp =
            post(token(sessionId, TokenType.PIN, "1234"));
        assertThat(body(pinResp).getNextRequiredToken()).isEqualTo(TokenType.OTP);

        ResponseEntity<AuthenticateResponse> otpResp =
            post(token(sessionId, TokenType.OTP, "123456"));

        assertThat(otpResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(otpResp).getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(body(otpResp).getCurrentLevel()).isEqualTo(AuthLevel.ELEVATED);
    }

    private static List<String> logMessages(AuthenticateResponse resp) {
        List<ProcessingEvent> log = resp.getProcessingLog();
        return log == null ? java.util.Collections.emptyList()
            : log.stream().map(ProcessingEvent::getMessage).collect(java.util.stream.Collectors.toList());
    }
}
