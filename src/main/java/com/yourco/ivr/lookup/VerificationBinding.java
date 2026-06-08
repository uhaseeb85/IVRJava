package com.yourco.ivr.lookup;

import lombok.Data;

import java.util.Map;

/**
 * Binds a token type to a backend {@link TokenLookupService}.
 *
 * <p>Bindings are wired <strong>in code</strong> via {@link VerificationBindings} (no per-brand
 * config, no UI). When a binding exists for a token, the engine runs the named lookup service
 * after the format validator passes.
 */
@Data
public class VerificationBinding {

    /** Id of a registered {@link TokenLookupService}. Required. */
    private String serviceId;

    /**
     * Optional params passed to the service (e.g. region, dataset). Secrets must be referenced
     * by alias, never stored here as plaintext.
     */
    private Map<String, String> params;

    /**
     * When the backend is unavailable (timeout/error): {@code true} (default) fails the token;
     * {@code false} skips verification and lets the format check stand.
     */
    private boolean failClosed = true;

    public VerificationBinding() {
    }

    public VerificationBinding(String serviceId, Map<String, String> params, boolean failClosed) {
        this.serviceId = serviceId;
        this.params = params;
        this.failClosed = failClosed;
    }
}
