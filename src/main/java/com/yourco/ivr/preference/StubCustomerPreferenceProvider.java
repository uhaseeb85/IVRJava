package com.yourco.ivr.preference;

import com.yourco.ivr.domain.CustomerPreference;
import org.springframework.stereotype.Component;

@Component
public class StubCustomerPreferenceProvider implements CustomerPreferenceProvider {

    @Override
    public CustomerPreference getPreferences(String partyId, String brandId) {
        return new CustomerPreference();
    }
}
