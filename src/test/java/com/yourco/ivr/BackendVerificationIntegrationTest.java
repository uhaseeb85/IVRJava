package com.yourco.ivr;

import com.yourco.ivr.api.dto.AuthenticateRequest;
import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.LevelRule;
import com.yourco.ivr.domain.config.TokenPath;
import com.yourco.ivr.lookup.VerificationBinding;
import com.yourco.ivr.lookup.VerificationBindings;
import com.yourco.ivr.registry.BrandRulesRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the backend verification gate end-to-end. Backend verification is now wired in code
 * via {@link VerificationBindings} (no per-brand config, no UI). The {@link TestBindings}
 * configuration below binds {@link TokenType#ACCOUNT_NUMBER} to the shipped {@code stub-verify}
 * service, varying the stub outcome by brand id so each scenario (pass / reject / unavailable)
 * can be exercised with a format-valid account number.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendVerificationIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private BrandRulesRegistry rulesRegistry;

    /**
     * In-code bindings for the test: ACCOUNT_NUMBER → stub-verify, with the stub outcome chosen
     * by brand id. {@code @Primary} so it wins over the empty production
     * {@code DefaultVerificationBindings} when the engine injects {@link VerificationBindings}.
     */
    @TestConfiguration
    static class TestBindings {
        @Bean
        @Primary
        VerificationBindings testVerificationBindings() {
            Map<String, String> outcomeByBrand = new HashMap<>();
            outcomeByBrand.put("LOOKUP_PASS", "pass");
            outcomeByBrand.put("LOOKUP_FAIL", "fail");
            outcomeByBrand.put("LOOKUP_DOWN", "unavailable");
            return (brandId, tokenType) -> {
                if (tokenType != TokenType.ACCOUNT_NUMBER) return null;
                String outcome = outcomeByBrand.get(brandId);
                if (outcome == null) return null;
                return new VerificationBinding(
                    "stub-verify", Collections.singletonMap("outcome", outcome), true);
            };
        }
    }

    private void registerBrand(String brandId) {
        TokenPath path = new TokenPath();
        path.setPathIndex(0);
        path.setDescription("Account lookup");
        path.setRequiredTokens(Collections.singletonList(TokenType.ACCOUNT_NUMBER));

        LevelRule rule = new LevelRule();
        rule.setPaths(Collections.singletonList(path));
        rule.setMaxRetriesPerToken(3);

        BrandAuthConfig cfg = new BrandAuthConfig();
        cfg.setBrandId(brandId);
        Map<AuthLevel, LevelRule> levels = new HashMap<>();
        levels.put(AuthLevel.BASIC, rule);
        cfg.setLevelRules(levels);

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
        registerBrand("LOOKUP_PASS");
        String sessionId = startSession("LOOKUP_PASS", "5551110001");

        ResponseEntity<AuthenticateResponse> resp = submitAccount(sessionId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getStatus()).isEqualTo(SessionStatus.AUTHENTICATED);
        assertThat(resp.getBody().getCurrentLevel()).isEqualTo(AuthLevel.BASIC);
    }

    @Test
    void backendVerificationFail_doesNotAuthenticate() {
        registerBrand("LOOKUP_FAIL");
        String sessionId = startSession("LOOKUP_FAIL", "5551110002");

        ResponseEntity<AuthenticateResponse> resp = submitAccount(sessionId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // A well-formed account number that the backend rejects must NOT authenticate.
        assertThat(resp.getBody().getStatus()).isNotEqualTo(SessionStatus.AUTHENTICATED);
    }

    @Test
    void unavailableBackend_failsClosed() {
        registerBrand("LOOKUP_DOWN");
        String sessionId = startSession("LOOKUP_DOWN", "5551110003");

        ResponseEntity<AuthenticateResponse> resp = submitAccount(sessionId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getStatus()).isNotEqualTo(SessionStatus.AUTHENTICATED);
    }
}
