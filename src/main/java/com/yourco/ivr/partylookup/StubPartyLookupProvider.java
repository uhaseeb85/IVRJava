package com.yourco.ivr.partylookup;

import com.yourco.ivr.domain.Party;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Development/test implementation of {@link PartyLookupProvider}.
 *
 * <p><strong>Security note:</strong> this stub derives party identity, account status and
 * attributes purely from the caller-claimed ANI. It is enabled by default for dev/test but
 * must be disabled in production (set {@code ivr.party-lookup.stub-enabled=false}) and
 * replaced with a real provider — otherwise callers could self-select party attributes that
 * {@code levelDetermination} rules (e.g. {@code PARTY_ATTRIBUTE}) base auth levels on.
 *
 * <p>Always returns a single party, so disambiguation is never triggered:
 * <ul>
 *   <li>Default caller IDs resolve to an active, primary-ANI party whose
 *       {@code partyId} is {@code "STUB-{callerId}"} and whose {@code accountNumber}
 *       equals the caller ID (any submitted account-number token will match).</li>
 *   <li>{@link #PREMIUM_ANI} resolves to an active party flagged
 *       {@code segment=PREMIUM} — used to exercise brand {@code levelDetermination}
 *       rules keyed on party attributes (see the DEMO_BRAND config).</li>
 *   <li>{@link #INACTIVE_ANI} resolves to an inactive party — exercises rules that
 *       depend on {@code PARTY_ACTIVE} and the {@code defaultLevel} fallback.</li>
 * </ul>
 *
 * <p>Replace with a real {@link PartyLookupProvider} implementation before production use.
 */
@Component
@ConditionalOnProperty(name = "ivr.party-lookup.stub-enabled", havingValue = "true", matchIfMissing = true)
public class StubPartyLookupProvider implements PartyLookupProvider {

    /** ANI resolving to an active PREMIUM-segment party (for levelDetermination demos). */
    public static final String PREMIUM_ANI = "5550000123";

    /** ANI resolving to an inactive party (falls through levelDetermination to defaultLevel). */
    public static final String INACTIVE_ANI = "5550000999";

    @Override
    public List<Party> lookupByAni(String callerId) {
        if (PREMIUM_ANI.equals(callerId)) {
            return Collections.singletonList(premiumParty(callerId));
        }
        if (INACTIVE_ANI.equals(callerId)) {
            return Collections.singletonList(inactiveParty(callerId));
        }
        Party party = new Party();
        party.setPartyId("STUB-" + callerId);
        party.setAccountNumber(callerId);
        party.setActive(true);
        party.setPrimaryAni(true);
        return Collections.singletonList(party);
    }

    private static Party premiumParty(String callerId) {
        Party party = new Party();
        party.setPartyId("STUB-" + callerId);
        party.setAccountNumber(callerId);
        party.setActive(true);
        party.setPrimaryAni(true);
        party.getAdditionalAttributes().put("segment", "PREMIUM");
        return party;
    }

    private static Party inactiveParty(String callerId) {
        Party party = new Party();
        party.setPartyId("STUB-" + callerId);
        party.setAccountNumber(callerId);
        party.setActive(false);
        party.setPrimaryAni(false);
        return party;
    }
}
