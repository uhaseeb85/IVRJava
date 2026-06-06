package com.yourco.ivr.lookup.impl;

import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.lookup.LookupRequest;
import com.yourco.ivr.lookup.LookupResult;
import com.yourco.ivr.lookup.TokenLookupService;
import com.yourco.ivr.validator.ValidationErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Development/test lookup service. Verifies nothing against a real backend — it returns a
 * configurable outcome so the whole feature can be exercised end-to-end before real
 * integrations exist. Analogue of {@code StubPartyLookupProvider} /
 * {@code StubCustomerPreferenceProvider}.
 *
 * <p>Outcome is driven by the binding param {@code outcome}:
 * <ul>
 *   <li>{@code "pass"} (default) → verified</li>
 *   <li>{@code "fail"}            → {@link ValidationErrorCode#NOT_FOUND}</li>
 *   <li>{@code "unavailable"}     → throws, to exercise the engine's fail-closed/open policy</li>
 * </ul>
 */
@Component
public class StubLookupService implements TokenLookupService {

    private static final Logger log = LoggerFactory.getLogger(StubLookupService.class);

    @Override
    public String id() { return "stub-verify"; }

    @Override
    public String displayName() { return "Stub Verifier (configurable)"; }

    @Override
    public String description() {
        return "Development stub. Returns a configurable outcome via the 'outcome' param "
            + "(pass | fail | unavailable). Does not call any real backend.";
    }

    @Override
    public Set<TokenType> supportedTokens() {
        return EnumSet.allOf(TokenType.class);
    }

    @Override
    public LookupResult verify(LookupRequest request) {
        String outcome = request.getParams().getOrDefault("outcome", "pass").trim().toLowerCase();
        // Note: token value is never logged — only the type, brand and outcome.
        log.info("LOOKUP stub brand={} token={} outcome={}",
            request.getBrandId(), request.getTokenType(), outcome);
        switch (outcome) {
            case "fail":
                return LookupResult.fail(ValidationErrorCode.NOT_FOUND, "Stub configured to fail");
            case "unavailable":
                throw new IllegalStateException("Stub configured to be unavailable");
            case "pass":
            default:
                return LookupResult.ok();
        }
    }
}
