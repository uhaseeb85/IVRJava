package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.TokenType;
import lombok.Data;

import java.util.Map;

@Data
public class BrandAuthConfig {
    private String brandId;
    private Map<AuthLevel, LevelRule> levelRules;
    private DisambiguationConfig disambiguation;

    /**
     * Optional, brand-level binding of a token type to a backend lookup/verification service.
     * Null or absent ⇒ tokens are format-validated only (legacy behavior).
     */
    private Map<TokenType, VerificationBinding> verificationSources;

    public DisambiguationConfig getDisambiguation() {
        if (disambiguation == null) {
            disambiguation = new DisambiguationConfig();
        }
        return disambiguation;
    }

    /** Null-safe lookup of the verification binding configured for a token, or null if none. */
    public VerificationBinding verificationSourceFor(TokenType tokenType) {
        return verificationSources == null ? null : verificationSources.get(tokenType);
    }
}