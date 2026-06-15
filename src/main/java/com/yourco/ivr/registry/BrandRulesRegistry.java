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
 * <p><strong>Atomic reload:</strong> a full reload is performed via {@link #replaceAll(Map)},
 * which swaps the backing map reference in a single volatile write. Readers calling {@link #get}
 * always see either the complete old map or the complete new map — there is never a window where
 * brands are missing. (The previous {@code clear()}-then-reload approach exposed such a window;
 * it has been removed.)
 *
 * <p>Only {@link com.yourco.ivr.service.BrandService} should mutate this registry.
 * Calling {@link #register} directly from outside the service bypasses config validation.
 */
@Component
public class BrandRulesRegistry {

    /**
     * Backing store. {@code volatile} so that {@link #replaceAll(Map)} can atomically swap the
     * whole map reference; the {@link ConcurrentHashMap} value itself handles concurrent
     * single-key {@link #register}/{@link #remove} mutations.
     */
    private volatile Map<String, BrandAuthConfig> configs = new ConcurrentHashMap<>();

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
     * Atomically replaces the entire set of loaded brand configs with {@code newConfigs}.
     *
     * <p>A fresh map is built from {@code newConfigs} and swapped in via a single volatile
     * write, so concurrent {@link #get} calls never observe a partially-loaded registry. Used by
     * {@link com.yourco.ivr.service.BrandService#refreshRegistry()} to reload from disk without a
     * gap where brands are absent.
     */
    public void replaceAll(Map<String, BrandAuthConfig> newConfigs) {
        this.configs = new ConcurrentHashMap<>(newConfigs);
    }

    /** Returns the set of brand IDs currently loaded in the registry. */
    public Set<String> getAllBrandIds() {
        return configs.keySet();
    }
}
