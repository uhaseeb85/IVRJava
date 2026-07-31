package com.yourco.ivr.preference;

import com.yourco.ivr.domain.CustomerPreference;

/**
 * SPI for loading per-customer authentication preferences after a party is identified.
 *
 * <p>Preferences are loaded once per session, immediately after disambiguation resolves
 * (or at session start for single-party lookups), and stored on the
 * {@link com.yourco.ivr.domain.IvrSession}. The engine uses them to filter which tokens
 * are offered:
 * <ul>
 *   <li>{@link com.yourco.ivr.domain.CustomerPreference#getBlockedTokens()} — the engine
 *       skips blocked tokens and tries backup alternatives or advances to the next path.</li>
 *   <li>{@link com.yourco.ivr.domain.CustomerPreference#getMaxAllowedLevel()} — caps the
 *       reachable auth level; escalation beyond it is rejected by the engine.</li>
 * </ul>
 *
 * <p>Replace {@link StubCustomerPreferenceProvider} with a real implementation that reads
 * from your CRM or compliance system. Register it as a Spring {@code @Component}.
 */
public interface CustomerPreferenceProvider {
    /**
     * Returns authentication preferences for the given party and brand.
     *
     * @param partyId  the resolved party identifier
     * @param brandId  the brand the session belongs to
     * @return preferences object; must not be {@code null} (return an empty
     *         {@link com.yourco.ivr.domain.CustomerPreference} for no restrictions)
     */
    CustomerPreference getPreferences(String partyId, String brandId);
}
