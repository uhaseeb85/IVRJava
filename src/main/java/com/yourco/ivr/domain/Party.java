package com.yourco.ivr.domain;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * A customer account record returned by {@link com.yourco.ivr.partylookup.PartyLookupProvider}.
 *
 * <p>When an ANI lookup returns multiple parties the
 * {@link com.yourco.ivr.engine.DisambiguationEngine} uses the identifying fields
 * ({@code accountNumber}, {@code dateOfBirth}, {@code ssnLast4}, {@code cardLast4}) to narrow
 * the list down to a single match before authentication begins.
 *
 * <p><strong>Security:</strong> the identifying fields contain sensitive PII and must never
 * appear in logs. They are used only for in-memory comparison during disambiguation.
 */
@Data
public class Party {
    /** Unique identifier for this party in the customer system of record. */
    private String partyId;

    /** Full account number; used as a disambiguation token and as a lookup key. */
    private String accountNumber;

    /** Date of birth in ISO local date format (YYYY-MM-DD); used for disambiguation. */
    private String dateOfBirth;

    /** Last four digits of Social Security Number; used for disambiguation. */
    private String ssnLast4;

    /** Last four digits of payment card; used for disambiguation. */
    private String cardLast4;

    /** Postal/ZIP code; not currently used by the engine but available for custom rules. */
    private String zipCode;

    /** Whether this account is currently active; inactive parties are filtered by {@code EXCLUDE_INACTIVE} rule. */
    private boolean active;

    /**
     * Whether this party's phone number is registered as the primary ANI on the account.
     * The {@code PREFER_PRIMARY_ANI} disambiguation rule narrows to primary-ANI parties first.
     */
    private boolean primaryAni;

    /**
     * Flexible key-value bag for integration-specific attributes not covered by the standard
     * fields. Accessible via {@link #getAttribute(String)}.
     */
    private Map<String, String> additionalAttributes;

    public Party() {
        this.additionalAttributes = new HashMap<>();
    }

    /**
     * Convenience accessor for {@code additionalAttributes}; returns {@code null} if the key
     * is absent or the map is not initialised.
     */
    public String getAttribute(String key) {
        return additionalAttributes != null ? additionalAttributes.get(key) : null;
    }
}
