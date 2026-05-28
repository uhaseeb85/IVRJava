package com.yourco.ivr.registry;

import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.exception.UnknownBrandException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BrandRulesRegistry {

    private final Map<String, BrandAuthConfig> configs = new ConcurrentHashMap<>();

    public void register(BrandAuthConfig config) {
        configs.put(config.getBrandId(), config);
    }

    public BrandAuthConfig get(String brandId) {
        BrandAuthConfig config = configs.get(brandId);
        if (config == null) {
            throw new UnknownBrandException(brandId);
        }
        return config;
    }

    public boolean contains(String brandId) {
        return configs.containsKey(brandId);
    }

    public void remove(String brandId) {
        configs.remove(brandId);
    }

    public void clear() {
        configs.clear();
    }

    public Set<String> getAllBrandIds() {
        return configs.keySet();
    }
}