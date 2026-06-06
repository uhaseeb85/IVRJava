package com.yourco.ivr;

import com.yourco.ivr.api.dto.AuthenticateRequest;
import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.LevelRule;
import com.yourco.ivr.domain.config.TokenPath;
import com.yourco.ivr.domain.config.VerificationBinding;
import com.yourco.ivr.registry.BrandRulesRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the configurable backend lookup/verification feature end-to-end:
 * brand config binds a token to a {@code TokenLookupService}, and the engine runs it after
 * the format check. Uses the shipped {@code stub-verify} service whose outcome is driven by
 * binding params. Brands are registered directly in the registry (no file writes).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LookupServiceIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private BrandRulesRegistry rulesRegistry;

    private void registerBrand(String brandId, String stubOutcome) {
        TokenPath path = new TokenPath();
        path.setPathIndex(0);
        path.setDescription("Account lookup");
        path.setRequiredTokens(Collections.singletonList(TokenType.ACCOUNT_NUMBER));

        LevelRule rule = new LevelRule();
        rule.setPaths(Collections.singletonList(path));
        rule.setMaxRetriesPerToken(3);

        VerificationBinding binding = new VerificationBinding();
        binding.setServiceId("stub-verify");
        binding.setParams(Collections.singletonMap("outcome", stubOutcome));

        Map<TokenType, VerificationBinding> sources = new HashMap<>();
        sources.put(TokenType.ACCOUNT_NUMBER, binding);

        BrandAuthConfig cfg = new BrandAuthConfig();
        cfg.setBrandId(brandId);
        Map<AuthLevel, LevelRule> levels = new HashMap<>();
        levels.put(AuthLevel.BASIC, rule);
        cfg.setLevelRules(levels);
        cfg.setVerificationSources(sources);

        rulesRegistry.register(cfg);
    }

    private ResponseEntity<AuthenticateResponse> post(AuthenticateRequest req) {
        return rest.postForEntity("/ivr/authenticate", req, AuthenticateResponse.class);
    }

    private String startSession(String brandId, String callerId) {
        AuthenticateRequest start = new AuthenticateRequest();
        start.setBrandId(brandId);
        start.setCallerId(callerId);
        start.setTargetLevel(AuthLevel.BASIC);
        ResponseEntity<AuthenticateResponse> resp = post(start);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getNextRequiredToken()).isEqualTo(TokenType.ACCOUNT_NUMBER);
        return resp.getBody().getSessionId();
    }

    private ResponseEntity<AuthenticateResponse> submitAccount(String sessionId) {
        AuthenticateRequest token = new AuthenticateRequest();
        token.setSessionId(sessionId);
        token.setTokenType(TokenType.ACCOUNT_NUMBER);
        token.setTokenValue("123456789");
        return post(token);
    }

    @Test
    void backendVerificationPass_authenticates() {
        registerBrand("LOOKUP_PASS", "pass");
        String sessionId = startSession("LOOKUP_PASS", "5551110001");

        ResponseEntity<AuthenticateResponse> resp = submitAccount(sessionId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(resp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.BASIC);
    }

    @Test
    void backendVerificationFail_doesNotAuthenticate() {
        registerBrand("LOOKUP_FAIL", "fail");
        String sessionId = startSession("LOOKUP_FAIL", "5551110002");

        ResponseEntity<AuthenticateResponse> resp = submitAccount(sessionId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // A well-formed account number that the backend rejects must NOT authenticate.
        assertThat(resp.getBody().getStatus()).isNotEqualTo(SessionStatus.AUTHENTICATED);
    }

    @Test
    void unavailableBackend_failsClosed() {
        registerBrand("LOOKUP_DOWN", "unavailable");
        String sessionId = startSession("LOOKUP_DOWN", "5551110003");

        ResponseEntity<AuthenticateResponse> resp = submitAccount(sessionId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getStatus()).isNotEqualTo(SessionStatus.AUTHENTICATED);
    }

    @Test
    void discoveryEndpoint_listsStubService() {
        ResponseEntity<String> resp = rest.getForEntity("/api/lookup-services", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("stub-verify");
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateRejectsUnknownService() {
        BrandAuthConfig cfg = brandReferencing("nope-service");
        ResponseEntity<Map> resp = rest.postForEntity("/api/brands/validate", cfg, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("valid")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateAcceptsKnownService() {
        BrandAuthConfig cfg = brandReferencing("stub-verify");
        ResponseEntity<Map> resp = rest.postForEntity("/api/brands/validate", cfg, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("valid")).isEqualTo(Boolean.TRUE);
    }

    private BrandAuthConfig brandReferencing(String serviceId) {
        TokenPath path = new TokenPath();
        path.setPathIndex(0);
        path.setRequiredTokens(Collections.singletonList(TokenType.SSN_LAST4));
        LevelRule rule = new LevelRule();
        rule.setPaths(Collections.singletonList(path));
        rule.setMaxRetriesPerToken(3);

        VerificationBinding binding = new VerificationBinding();
        binding.setServiceId(serviceId);
        Map<TokenType, VerificationBinding> sources = new HashMap<>();
        sources.put(TokenType.SSN_LAST4, binding);

        BrandAuthConfig cfg = new BrandAuthConfig();
        cfg.setBrandId("VALIDATE_ONLY");
        Map<AuthLevel, LevelRule> levels = new HashMap<>();
        levels.put(AuthLevel.STANDARD, rule);
        cfg.setLevelRules(levels);
        cfg.setVerificationSources(sources);
        return cfg;
    }
}
