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
 *       describing which token paths are required and how many retries are allowed.</li>
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
     */
    private Map<AuthLevel, LevelRule> levelRules;

    /**
     * When {@code true}, the goal is identification, not authentication: the flow stops as soon
     * as a single party is resolved and reports {@code AUTHENTICATED} with {@code currentLevel=NONE}.
     * No auth tokens are collected and escalation is not permitted. Defaults to {@code false}.
     */
    private boolean identificationOnly;
}