package com.yourco.ivr.preference;

import com.yourco.ivr.domain.CustomerPreference;

public interface CustomerPreferenceProvider {
    CustomerPreference getPreferences(String partyId, String brandId);
}
