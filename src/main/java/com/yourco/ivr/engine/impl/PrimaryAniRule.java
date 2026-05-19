package com.yourco.ivr.engine.impl;

import com.yourco.ivr.domain.Party;
import com.yourco.ivr.engine.DisambiguationRule;

import java.util.List;
import java.util.stream.Collectors;

public class PrimaryAniRule implements DisambiguationRule {

    @Override
    public List<Party> apply(List<Party> parties) {
        List<Party> primary = parties.stream()
            .filter(Party::isPrimaryAni)
            .collect(Collectors.toList());
        return primary.isEmpty() ? parties : primary;
    }
}
