package com.yourco.ivr.preference;

import com.yourco.ivr.domain.CustomerPreference;
import org.springframework.stereotype.Component;

/**
 * Development/test implementation of {@link CustomerPreferenceProvider}.
 *
 * <p>Returns an empty {@link CustomerPreference} for every party — no blocked tokens and
 * no max-level cap. This means all token types are offered to every caller and authentication
 * can reach any level configured in the brand rules.
 *
 * <p>Replace with a real implementation before production use.
 */
@Component
public class StubCustomerPreferenceProvider implements CustomerPreferenceProvider {

    @Override
    public CustomerPreference getPreferences(String partyId, String brandId) {
        return new CustomerPreference();
    }
}
