package com.yourco.ivr.domain.config;

import lombok.Data;

import java.util.Map;

/**
 * Binds a token type (within a brand) to a backend lookup service.
 *
 * <p>Lives in brand config under {@code BrandAuthConfig.verificationSources}. When present for a
 * token, the engine runs the named {@link com.yourco.ivr.lookup.TokenLookupService} after the
 * format validator passes.
 */
@Data
public class VerificationBinding {

    /** Id of a registered {@link com.yourco.ivr.lookup.TokenLookupService}. Required. */
    private String serviceId;

    /**
     * Optional per-brand params passed to the service (e.g. region, dataset). Secrets must be
     * referenced by alias, never stored here as plaintext.
     */
    private Map<String, String> params;

    /**
     * When the backend is unavailable (timeout/error): {@code true} (default) fails the token;
     * {@code false} skips verification and lets the format check stand.
     */
    private boolean failClosed = true;
}
