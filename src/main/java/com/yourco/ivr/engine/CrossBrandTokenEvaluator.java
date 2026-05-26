package com.yourco.ivr.engine;

import com.yourco.ivr.domain.CrossBrandTokenRecord;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.TokenType;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Tracks which tokens were validated in this session and which brand validated them.
 * This is provenance metadata only — it does NOT drive auth-level decisions.
 * Auth progress is determined solely by {@code IvrSession.validatedTokens}.
 */
@Component
public class CrossBrandTokenEvaluator {

    public void recordValidated(IvrSession session, TokenType type) {
        session.getCrossBrandTokens().put(type,
            new CrossBrandTokenRecord(session.getBrandId(), Instant.now()));
    }
}