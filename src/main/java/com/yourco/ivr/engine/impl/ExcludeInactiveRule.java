package com.yourco.ivr.engine.impl;

import com.yourco.ivr.domain.Party;
import com.yourco.ivr.engine.DisambiguationRule;

import java.util.List;
import java.util.stream.Collectors;

public class ExcludeInactiveRule implements DisambiguationRule {

    @Override
    public List<Party> apply(List<Party> parties) {
        return parties.stream()
            .filter(Party::isActive)
            .collect(Collectors.toList());
    }
}
