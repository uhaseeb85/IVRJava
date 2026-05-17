package com.yourco.ivr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.exception.UnknownBrandException;
import com.yourco.ivr.registry.BrandRulesRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class BrandService {

    private static final Logger log = LoggerFactory.getLogger(BrandService.class);

    private final BrandRulesRegistry registry;
    private final ObjectMapper mapper;
    private final String configDir;

    @Autowired
    public BrandService(BrandRulesRegistry registry, ObjectMapper mapper,
                        @Value("${ivr.brands.config-dir:./config/brands}") String configDir) {
        this.registry = registry;
        this.mapper = mapper;
        this.configDir = configDir;
    }

    @PostConstruct
    public void init() {
        File dir = new File(configDir);
        if (!dir.exists()) {
            dir.mkdirs();
            log.info("Created brand config directory: {}", configDir);
        }
    }

    public List<BrandAuthConfig> listAll() {
        // Get brands from the registry and from the filesystem
        List<BrandAuthConfig> result = new ArrayList<>();
        File dir = new File(configDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                try {
                    BrandAuthConfig config = mapper.readValue(file, BrandAuthConfig.class);
                    result.add(config);
                } catch (Exception e) {
                    log.warn("Failed to read brand file: {}", file.getName(), e);
                }
            }
        }
        return result;
    }

    public BrandAuthConfig get(String brandId) {
        // Try registry first (fast path), fall back to reading file
        try {
            return registry.get(brandId);
        } catch (UnknownBrandException e) {
            // Not in registry, try to read from file
            File file = getBrandFile(brandId);
            if (file.exists()) {
                try {
                    BrandAuthConfig config = mapper.readValue(file, BrandAuthConfig.class);
                    registry.register(config);
                    return config;
                } catch (IOException ex) {
                    throw new RuntimeException("Failed to read brand config: " + brandId, ex);
                }
            }
            throw e;
        }
    }

    public BrandAuthConfig save(BrandAuthConfig config) {
        String brandId = config.getBrandId();
        if (brandId == null || brandId.trim().isEmpty()) {
            throw new IllegalArgumentException("Brand ID is required");
        }

        File file = getBrandFile(brandId);
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, config);
            registry.register(config);
            log.info("Saved brand config: {}", brandId);
            return config;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save brand config: " + brandId, e);
        }
    }

    public BrandAuthConfig update(String brandId, BrandAuthConfig config) {
        config.setBrandId(brandId);
        return save(config);
    }

    public void delete(String brandId) {
        File file = getBrandFile(brandId);
        if (file.exists()) {
            file.delete();
            log.info("Deleted brand config: {}", brandId);
        }
        // Reload registry from remaining files to keep in sync
        refreshRegistry();
    }

    public ValidationResult validate(BrandAuthConfig config) {
        // Basic validations
        if (config.getBrandId() == null || config.getBrandId().trim().isEmpty()) {
            return ValidationResult.error("Brand ID is required");
        }
        if (config.getLevelRules() == null || config.getLevelRules().isEmpty()) {
            return ValidationResult.error("At least one level rule is required");
        }
        return ValidationResult.ok();
    }

    public void refreshRegistry() {
        registry.clear();
        loadFromDirectory();
    }

    public void loadFromDirectory() {
        File dir = new File(configDir);
        if (!dir.exists()) return;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                try {
                    BrandAuthConfig config = mapper.readValue(file, BrandAuthConfig.class);
                    if (config.getBrandId() != null) {
                        registry.register(config);
                        log.info("Loaded brand config: {} from {}", config.getBrandId(), file.getName());
                    }
                } catch (Exception e) {
                    log.warn("Failed to load brand file: {}", file.getName(), e);
                }
            }
        }
    }

    private File getBrandFile(String brandId) {
        String sanitized = brandId.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
        return new File(configDir, sanitized + ".json");
    }

    public static class ValidationResult {
        private final boolean valid;
        private final String message;

        private ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }

        public boolean isValid() { return valid; }
        public String getMessage() { return message; }
    }
}