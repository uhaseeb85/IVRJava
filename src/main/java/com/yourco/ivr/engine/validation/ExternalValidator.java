package com.yourco.ivr.engine.validation;

import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.Party;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.engine.PartyTokenFields;
import com.yourco.ivr.lookup.LookupRequest;
import com.yourco.ivr.lookup.LookupResult;
import com.yourco.ivr.lookup.LookupServiceRegistry;
import com.yourco.ivr.lookup.TokenLookupService;
import com.yourco.ivr.lookup.VerificationBinding;
import com.yourco.ivr.lookup.VerificationBindings;
import com.yourco.ivr.registry.BrandRulesRegistry;
import com.yourco.ivr.validator.TokenValidationContext;
import com.yourco.ivr.validator.TokenValidator;
import com.yourco.ivr.validator.TokenValidatorRegistry;
import com.yourco.ivr.validator.ValidationErrorCode;
import com.yourco.ivr.validator.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Runs the two-stage validation pipeline for a submitted token.
 *
 * <p>Stage 1 (format gate): cheap, in-process format check via {@link TokenValidatorRegistry}.
 * Stage 1b (party-field verification): for identification-only brands.
 * Stage 2 (backend gate): only if a {@link VerificationBinding} is configured.
 */
@Component
public class ExternalValidator {

    private static final Logger log = LoggerFactory.getLogger(ExternalValidator.class);

    private final TokenValidatorRegistry validatorRegistry;
    private final LookupServiceRegistry lookupRegistry;
    private final VerificationBindings verificationBindings;
    private final BrandRulesRegistry rulesRegistry;

    public ExternalValidator(TokenValidatorRegistry validatorRegistry,
                             LookupServiceRegistry lookupRegistry,
                             VerificationBindings verificationBindings,
                             BrandRulesRegistry rulesRegistry) {
        this.validatorRegistry = validatorRegistry;
        this.lookupRegistry = lookupRegistry;
        this.verificationBindings = verificationBindings;
        this.rulesRegistry = rulesRegistry;
    }

    public ValidationResult validate(IvrSession session, TokenType tokenType, String tokenValue) {
        // Stage 1: format gate (cheap, no network)
        TokenValidator validator = validatorRegistry.resolve(session.getBrandId(), tokenType);
        TokenValidationContext ctx = new TokenValidationContext(
            tokenType, tokenValue, session.getCallerId(),
            session.getCollectedTokens(), session.getBrandId()
        );
        ValidationResult formatResult = validator.validate(ctx);
        if (!formatResult.isValid()) {
            return formatResult;
        }

        // Party-field verification (identification-only brands)
        BrandAuthConfig config = rulesRegistry.get(session.getBrandId());
        if (config.isIdentificationOnly()) {
            ValidationResult partyResult = verifyAgainstParty(session, tokenType, tokenValue);
            if (!partyResult.isValid()) {
                return partyResult;
            }
        }

        // Stage 2: backend verification (only if this token is bound to a service)
        VerificationBinding binding = verificationBindings.bindingFor(session.getBrandId(), tokenType);
        if (binding == null) {
            return ValidationResult.ok();
        }
        return verifyAgainstBackend(session, tokenType, tokenValue, binding);
    }

    private ValidationResult verifyAgainstParty(IvrSession session, TokenType tokenType,
                                                 String tokenValue) {
        Party party = session.getMatchedParty();
        if (party == null) return ValidationResult.ok();

        Function<Party, String> extractor = PartyTokenFields.FIELD_ACCESSORS.get(tokenType);
        if (extractor == null) return ValidationResult.ok();

        String expectedValue = extractor.apply(party);
        if (expectedValue == null) return ValidationResult.ok();

        if (expectedValue.equals(tokenValue)) {
            return ValidationResult.ok();
        }
        log.info("PARTY_VERIFY [{}] token={} value mismatch for party={}",
            session.getSessionId(), tokenType, party.getPartyId());
        return ValidationResult.fail(ValidationErrorCode.VERIFICATION_FAILED);
    }

    private ValidationResult verifyAgainstBackend(IvrSession session,
                                                   TokenType tokenType,
                                                   String tokenValue,
                                                   VerificationBinding binding) {
        try {
            TokenLookupService service = lookupRegistry.get(binding.getServiceId());
            LookupRequest request = new LookupRequest(
                tokenType, tokenValue, session.getCallerId(),
                session.getBrandId(), session.getCollectedTokens(), binding.getParams()
            );
            LookupResult result = service.verify(request);
            log.info("LOOKUP [{}] brand={} token={} service={} result={}",
                session.getSessionId(), session.getBrandId(), tokenType,
                binding.getServiceId(), result.isVerified() ? "VERIFIED" : "REJECTED");
            return result.isVerified()
                ? ValidationResult.ok()
                : ValidationResult.fail(result.getCode() != null
                    ? result.getCode() : ValidationErrorCode.VERIFICATION_FAILED);
        } catch (RuntimeException ex) {
            log.warn("LOOKUP [{}] brand={} token={} service={} UNAVAILABLE failClosed={}: {}",
                session.getSessionId(), session.getBrandId(), tokenType,
                binding.getServiceId(), binding.isFailClosed(), ex.getMessage());
            return binding.isFailClosed()
                ? ValidationResult.fail(ValidationErrorCode.VERIFICATION_UNAVAILABLE)
                : ValidationResult.ok();
        }
    }
}