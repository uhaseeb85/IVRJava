package com.yourco.ivr.lookup;

import com.yourco.ivr.domain.TokenType;

import java.util.Set;

/**
 * A pluggable backend verification service ("lookup service").
 *
 * <p>Where a {@link com.yourco.ivr.validator.TokenValidator} only checks a token's
 * <em>format</em>, a {@code TokenLookupService} verifies the token value against a real
 * backend system of record (e.g. confirm an SSN matches the customer record in an external
 * system). Each implementation is a Spring {@code @Component}; they are auto-discovered into
 * {@link LookupServiceRegistry}. Adding a new backend integration is just dropping a new bean.
 *
 * <p>A brand wires a service to a token via config — see
 * {@code BrandAuthConfig.verificationSources}. No per-brand code is required.
 */
public interface TokenLookupService {

    /** Stable, unique registry key (e.g. {@code "experian-ssn"}). Stored in brand config. */
    String id();

    /** Human-readable label shown in the Brand Editor dropdown. */
    String displayName();

    /** Short description shown as UI helptext. */
    String description();

    /** Token types this service is able to verify; the UI offers it only for these. */
    Set<TokenType> supportedTokens();

    /**
     * Verify a token value against the backend. Implementations MUST NOT log the raw token
     * value (see project security rules) and SHOULD enforce their own timeout.
     */
    LookupResult verify(LookupRequest request);
}
