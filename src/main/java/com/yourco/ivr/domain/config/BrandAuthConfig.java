package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.AuthLevel;
import lombok.Data;

import java.util.Map;

/**
 * Top-level brand configuration loaded from {@code ./config/brands/{brandId}.json}.
 *
 * <p>A brand config drives the entire authentication flow for one brand:
 * <ul>
 *   <li>{@code levelRules} — maps each supported {@link AuthLevel} to a {@link LevelRule}
 *       describing which token paths are required and how many retries are allowed.
 *       Also used by identification-only brands to optionally define identification tokens
 *       under the {@code NONE} level key. These tokens are matched against party fields to
 *       confirm caller identity.</li>
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

    /**
     * Authentication rules keyed by target level. Required for authentication brands;
     * may be omitted/empty when {@link #identificationOnly} is {@code true}.
     * Also used by identification-only brands to optionally define identification tokens
     * under the {@code NONE} level key. These tokens are matched against party fields
     * to confirm caller identity.
     */
    private Map<AuthLevel, LevelRule> levelRules;

    /**
     * When {@code true}, the flow identifies the caller to a single party. If
     * {@code levelRules[NONE]} is defined, those tokens are collected and matched
     * against party fields for identity confirmation. Without rules, finalizes
     * immediately after party resolution. Escalation is not permitted.
     * Defaults to {@code false}.
     */
    private boolean identificationOnly;

    /**
     * Optional declarative rules that derive the session's target auth level from call
     * conditions (ANI match, party attributes, ...) instead of accepting a caller-supplied
     * {@code targetLevel}. Evaluated once the caller's party is resolved; the derived level
     * replaces the request's target level. Brands that omit this section keep the legacy
     * behavior (caller-supplied target level used as-is).
     *
     * @see LevelDeterminationConfig
     */
    private LevelDeterminationConfig levelDetermination;
}