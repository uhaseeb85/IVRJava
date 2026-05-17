package com.yourco.ivr.engine;

import com.yourco.ivr.domain.CrossBrandTokenRecord;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.TokenType;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class CrossBrandTokenEvaluator {

    /**
     * Checks whether a token can be accepted without external validation
     * via cross-brand token sharing. Any token validated in a prior brand
     * within the same session is reusable.
     */
    public boolean isAccepted(IvrSession session,
                              TokenType tokenType) {

        // Any token validated in this session is reusable
        CrossBrandTokenRecord record = session.getCrossBrandTokens().get(tokenType);
        if (record == null) return false;

        return true;
    }

    /**
     * Record a token validated in this session for potential reuse by other brands.
     */
    public void recordValidated(IvrSession session, TokenType type) {
        session.getCrossBrandTokens().put(type,
            new CrossBrandTokenRecord(session.getBrandId(), Instant.now()));
    }
}