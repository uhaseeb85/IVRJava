package com.yourco.ivr.domain;

import lombok.Data;

import java.util.Set;

/**
 * Per-customer authentication preferences, loaded once per session by
 * {@link com.yourco.ivr.preference.CustomerPreferenceProvider} after a party is resolved.
 *
 * <p>These preferences constrain which tokens the engine will offer to the caller:
 * <ul>
 *   <li>Tokens in {@code blockedTokens} are never prompted — if the primary required token is
 *       blocked, the engine tries backup alternatives; if all are blocked it advances to the
 *       next fallback path.</li>
 *   <li>{@code maxAllowedLevel} caps the authentication level the session can reach, regardless
 *       of the brand config or caller request. (Currently stored but not enforced by the
 *       engine — intended for future use.)</li>
 * </ul>
 *
 * <p>Loaded by {@link com.yourco.ivr.preference.CustomerPreferenceProvider}. The stub
 * implementation returns an empty preference object (no restrictions). Real implementations
 * should query the CRM or compliance system.
 */
@Data
public class CustomerPreference {
    /**
     * Token types this customer has opted out of or is prohibited from using
     * (e.g. voice print disabled for hearing-impaired flag, SSN blocked for privacy opt-out).
     * Null or empty means no restrictions.
     */
    private Set<TokenType> blockedTokens;

    /**
     * The maximum authentication level this customer is permitted to reach.
     * Null means no cap beyond what the brand config allows.
     */
    private AuthLevel maxAllowedLevel;
}
