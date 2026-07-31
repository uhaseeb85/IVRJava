package com.yourco.ivr.engine.impl;

import com.yourco.ivr.domain.Party;
import com.yourco.ivr.engine.DisambiguationRule;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Disambiguation pre-filter that removes parties marked as inactive.
 *
 * <p>Always applied by {@link com.yourco.ivr.engine.DisambiguationEngine} before token-based
 * narrowing begins (not configurable per brand). If all parties are inactive the candidate
 * list becomes empty and disambiguation fails.
 */
public class ExcludeInactiveRule implements DisambiguationRule {

    @Override
    public List<Party> apply(List<Party> parties) {
        return parties.stream()
            .filter(Party::isActive)
            .collect(Collectors.toList());
    }
}
