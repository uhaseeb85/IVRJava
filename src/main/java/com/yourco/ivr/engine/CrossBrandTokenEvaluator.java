package com.yourco.ivr.engine;

import com.yourco.ivr.domain.CrossBrandTokenRecord;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.TokenType;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class CrossBrandTokenEvaluator {

    /** Record a token validated in this session for provenance tracking. */
    public void recordValidated(IvrSession session, TokenType type) {
        session.getCrossBrandTokens().put(type,
            new CrossBrandTokenRecord(session.getBrandId(), Instant.now()));
    }
}