package com.yourco.ivr.registry;

import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.exception.UnknownBrandException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory cache of all loaded {@link BrandAuthConfig} objects, keyed by brand ID.
 *
 * <p>Populated at startup by {@link com.yourco.ivr.registry.BrandRulesLoader} via
 * {@link com.yourco.ivr.service.BrandService#loadFromDirectory()}, and kept in sync by
 * {@link com.yourco.ivr.service.BrandService#save}/{@code delete}/{@code update}.
 *
 * <p><strong>Known issue:</strong> {@link #clear()} followed by a reload in
 * {@link com.yourco.ivr.service.BrandService#refreshRegistry()} creates a brief window where
 * all brands are absent. For production use, implement an atomic swap (replace the map
 * reference rather than mutating it).
 *
 * <p>Only {@link com.yourco.ivr.service.BrandService} should mutate this registry.
 * Calling {@link #register} directly from outside the service bypasses config validation.
 */
@Component
public class BrandRulesRegistry {

    private final Map<String, BrandAuthConfig> configs = new ConcurrentHashMap<>();

    /**
     * Adds or replaces the config for {@code config.getBrandId()}.
     * Prefer calling this through {@link com.yourco.ivr.service.BrandService#save} so
     * validation runs before the registry is updated.
     */
    public void register(BrandAuthConfig config) {
        configs.put(config.getBrandId(), config);
    }

    /**
     * Returns the config for the given brand ID.
     *
     * @throws com.yourco.ivr.exception.UnknownBrandException if the brand is not loaded
     */
    public BrandAuthConfig get(String brandId) {
        BrandAuthConfig config = configs.get(brandId);
        if (config == null) {
            throw new UnknownBrandException(brandId);
        }
        return config;
    }

    /** Returns {@code true} if a config is loaded for {@code brandId}. */
    public boolean contains(String brandId) {
        return configs.containsKey(brandId);
    }

    /** Removes the config for {@code brandId}; no-op if not present. */
    public void remove(String brandId) {
        configs.remove(brandId);
    }

    /**
     * Removes all loaded brand configs. Used by
     * {@link com.yourco.ivr.service.BrandService#refreshRegistry()} — see the known-issue
     * note on the class for the race-condition caveat.
     */
    public void clear() {
        configs.clear();
    }

    /** Returns the set of brand IDs currently loaded in the registry. */
    public Set<String> getAllBrandIds() {
        return configs.keySet();
    }
}