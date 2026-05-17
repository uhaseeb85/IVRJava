package com.yourco.ivr.engine;

import com.yourco.ivr.domain.CrossBrandTokenRecord;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.TokenSharingPolicy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

@Component
public class CrossBrandTokenEvaluator {

    /**
     * Checks whether a token can be accepted without external validation
     * based on cross-brand sharing policies.
     */
    public boolean isAccepted(IvrSession session,
                              BrandAuthConfig config,
                              TokenType tokenType,
                              String tokenValue) {
        TokenSharingPolicy policy = config.getSharingPolicy();
        if (policy == null) return false;

        // 1. Globally shared — trusted from any brand
        if (policy.getGloballySharedTokens() != null
            && policy.getGloballySharedTokens().contains(tokenType)) {
            return session.getValidatedTokens().contains(tokenType);
        }

        // 2. Conditionally shared — trust only from listed brands
        Map<TokenType, Set<String>> conditional = policy.getConditionallySharedFrom();
        CrossBrandTokenRecord record = session.getCrossBrandTokens().get(tokenType);

        if (record == null) return false;

        if (conditional != null) {
            Set<String> trustedBrands = conditional.get(tokenType);
            if (trustedBrands == null || !trustedBrands.contains(record.getSourceBrandId())) {
                return false;
            }
        }

        // 3. TTL check
        int maxAge = policy.getCrossBrandTokenMaxAgeSeconds();
        if (maxAge > 0) {
            Instant expiry = record.getValidatedAt().plusSeconds(maxAge);
            if (Instant.now().isAfter(expiry)) return false;
        }

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