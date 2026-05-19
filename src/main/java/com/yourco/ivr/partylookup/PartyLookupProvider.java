package com.yourco.ivr.partylookup;

import com.yourco.ivr.domain.Party;

import java.util.List;

public interface PartyLookupProvider {
    List<Party> lookupByAni(String callerId);
}
