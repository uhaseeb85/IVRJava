package com.yourco.ivr.partylookup;

import com.yourco.ivr.domain.Party;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

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
