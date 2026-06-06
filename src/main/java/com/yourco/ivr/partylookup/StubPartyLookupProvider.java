package com.yourco.ivr.partylookup;

import com.yourco.ivr.domain.Party;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Development/test implementation of {@link PartyLookupProvider}.
 *
 * <p>Always returns a single active party whose {@code partyId} is {@code "STUB-{callerId}"}
 * and whose {@code accountNumber} equals the caller ID. This means:
 * <ul>
 *   <li>Disambiguation is never triggered (single-party result).</li>
 *   <li>Any account-number token submitted by the caller will match (value equals the ANI).</li>
 * </ul>
 *
 * <p>Replace with a real {@link PartyLookupProvider} implementation before production use.
 */
@Component
public class StubPartyLookupProvider implements PartyLookupProvider {

    @Override
    public List<Party> lookupByAni(String callerId) {
        Party party = new Party();
        party.setPartyId("STUB-" + callerId);
        party.setAccountNumber(callerId);
        party.setActive(true);
        party.setPrimaryAni(true);
        return Collections.singletonList(party);
    }
}
