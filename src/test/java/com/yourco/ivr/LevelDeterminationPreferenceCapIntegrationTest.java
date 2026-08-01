package com.yourco.ivr;

import com.yourco.ivr.api.dto.AuthenticateRequest;
import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.CustomerPreference;
import com.yourco.ivr.preference.CustomerPreferenceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies that the level derived by {@code levelDetermination} is clamped to the
 * customer's {@code maxAllowedLevel} preference — the same guard escalation enforces.
 * DEMO_BRAND derives STANDARD for a regular active caller; a customer capped at BASIC
 * must be led to BASIC instead, without revealing the cap value in the processing log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LevelDeterminationPreferenceCapIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private CustomerPreferenceProvider preferenceProvider;

    @Test
    void derivedLevelIsClampedByMaxAllowedLevelPreference() {
        CustomerPreference prefs = new CustomerPreference();
        prefs.setMaxAllowedLevel(AuthLevel.BASIC);
        when(preferenceProvider.getPreferences(anyString(), anyString())).thenReturn(prefs);

        AuthenticateRequest start = new AuthenticateRequest();
        start.setBrandId("DEMO_BRAND");
        start.setCallerId("5551234567"); // active party -> PARTY_ACTIVE rule -> STANDARD, then clamped

        ResponseEntity<AuthenticateResponse> resp =
            rest.postForEntity("/ivr/authenticate", start, AuthenticateResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthenticateResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTargetLevel()).isEqualTo(AuthLevel.BASIC);
        assertThat(body.getProcessingLog()).anyMatch(e ->
            e.getMessage().contains("Level determined: BASIC") && e.getMessage().contains("capped by customer preference"));
    }
}
