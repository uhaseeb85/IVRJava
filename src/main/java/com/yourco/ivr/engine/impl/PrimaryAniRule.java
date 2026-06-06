package com.yourco.ivr.engine.impl;

import com.yourco.ivr.domain.Party;
import com.yourco.ivr.engine.DisambiguationRule;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Disambiguation pre-filter that narrows the candidate list to parties whose phone number
 * is registered as the primary ANI on the account.
 *
 * <p>Enable by adding {@code {"type": "PREFER_PRIMARY_ANI"}} to a brand's
 * {@code disambiguation.rules} list. If no party has {@code primaryAni == true}, the full
 * input list is returned unchanged (i.e. this rule is non-destructive when no primary exists).
 */
public class PrimaryAniRule implements DisambiguationRule {

    @Override
    public List<Party> apply(List<Party> parties) {
        List<Party> primary = parties.stream()
            .filter(Party::isPrimaryAni)
            .collect(Collectors.toList());
        return primary.isEmpty() ? parties : primary;
    }
}
