package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.TokenType;
import lombok.Data;

import java.util.Map;

/**
 * Top-level brand configuration loaded from {@code ./config/brands/{brandId}.json}.
 *
 * <p>A brand config drives the entire authentication flow for one brand:
 * <ul>
 *   <li>{@code levelRules} — maps each supported {@link AuthLevel} to a {@link LevelRule}
 *       describing which token paths are required and how many retries are allowed.</li>
 *   <li>{@code disambiguation} — configures how multiple-party ANI matches are resolved.</li>
 *   <li>{@code verificationSources} — optionally binds token types to backend lookup
 *       services for real verification beyond format checks.</li>
 * </ul>
 *
 * <p>Brand configs are managed via {@link com.yourco.ivr.service.BrandService} and kept
 * in the {@link com.yourco.ivr.registry.BrandRulesRegistry} in-memory cache.
 * The {@link com.yourco.ivr.api.BrandController} REST endpoints expose CRUD operations.
 *
 * @see LevelRule
 * @see TokenPath
 * @see com.yourco.ivr.service.BrandService
 */
@Data
public class BrandAuthConfig {
    /** Unique brand identifier; must match the JSON filename (lowercased). */
    private String brandId;

    /** Authentication rules keyed by target level; at least one level is required. */
    private Map<AuthLevel, LevelRule> levelRules;

    /** Party disambiguation settings; lazily initialised with defaults if absent in JSON. */
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