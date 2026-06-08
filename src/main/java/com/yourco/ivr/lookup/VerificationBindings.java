package com.yourco.ivr.lookup;

import com.yourco.ivr.domain.TokenType;

/**
 * Resolves, in code, which backend {@link TokenLookupService} (if any) verifies a given token.
 *
 * <p>This replaces the former per-brand {@code verificationSources} config block: backend
 * verification is now wired in code rather than from brand JSON or the UI. The engine consults
 * this after the format gate passes — see {@code AuthEngine.validateExternally}.
 *
 * @see DefaultVerificationBindings
 */
public interface VerificationBindings {

    /**
     * @return the {@link VerificationBinding} for this brand/token, or {@code null} when the token
     *     has no backend gate (format check only — the default for every token).
     */
    VerificationBinding bindingFor(String brandId, TokenType tokenType);
}
