package com.yourco.ivr.engine;

import com.yourco.ivr.domain.Party;

import java.util.List;

public interface DisambiguationRule {
    List<Party> apply(List<Party> parties);
}
