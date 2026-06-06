package com.yourco.ivr.engine.impl;

import com.yourco.ivr.domain.Party;
import com.yourco.ivr.engine.DisambiguationRule;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Disambiguation pre-filter that removes parties marked as inactive.
 *
 * <p>Enable by adding {@code {"type": "EXCLUDE_INACTIVE"}} to a brand's
 * {@code disambiguation.rules} list. Applied before token-based narrowing begins.
 * If all parties are inactive the candidate list becomes empty and disambiguation fails.
 */
public class ExcludeInactiveRule implements DisambiguationRule {

    @Override
    public List<Party> apply(List<Party> parties) {
        return parties.stream()
            .filter(Party::isActive)
            .collect(Collectors.toList());
    }
}
