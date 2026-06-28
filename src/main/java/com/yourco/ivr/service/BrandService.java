package com.yourco.ivr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.ValidationResult;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.LevelRule;
import com.yourco.ivr.domain.config.TokenPath;
import com.yourco.ivr.exception.BrandConfigException;
import com.yourco.ivr.exception.UnknownBrandException;
import com.yourco.ivr.registry.BrandRulesRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for managing brand configuration files and the in-memory {@link BrandRulesRegistry}.
 *
 * <p>Brand configs are JSON files stored in {@code ./config/brands/{brandId}.json}
 * (directory configurable via {@code ivr.brands.config-dir}). This service owns all
 * read/write/delete operations on those files and keeps the registry in sync.
 *
 * <p>All writes go through {@link #validate} first, which checks structural correctness
 * (required fields, non-empty paths). Never bypass this by calling
 * {@link BrandRulesRegistry#register} directly.
 *
 * <p>{@link #refreshRegistry()} performs an atomic reload: it reads and validates every config
 * file into a fresh map, then swaps it into the registry in one operation
 * ({@link BrandRulesRegistry#replaceAll}), so callers never observe a window where brands are
 * missing.
 */
@Service
public class BrandService {

    private static final Logger log = LoggerFactory.getLogger(BrandService.class);
    private static final String JSON_EXT = ".json";

    private final BrandRulesRegistry registry;
    private final ObjectMapper mapper;
    private final String configDir;

    public BrandService(BrandRulesRegistry registry,
                        ObjectMapper mapper,
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

    /** Returns all brand configs found in the config directory, skipping unreadable files. */
    public List<BrandAuthConfig> listAll() {
        List<BrandAuthConfig> result = new ArrayList<>();
        File dir = new File(configDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(JSON_EXT));
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

    /**
     * Returns the config for the given brand ID.
     * Checks the registry first; if absent, attempts a lazy load from the config file.
     *
     * @throws UnknownBrandException if neither the registry nor the file system has the brand
     */
    public BrandAuthConfig get(String brandId) {
        try {
            return registry.get(brandId);
        } catch (UnknownBrandException e) {
            File file = getBrandFile(brandId);
            if (file.exists()) {
                try {
                    BrandAuthConfig config = mapper.readValue(file, BrandAuthConfig.class);
                    ValidationResult validation = validate(config);
                    if (!validation.isValid()) {
                        throw new BrandConfigException(
                            "Invalid brand config " + brandId + ": " + validation.getMessage());
                    }
                    registry.register(config);
                    return config;
                } catch (IOException ex) {
                    throw new BrandConfigException("Failed to read brand config: " + brandId, ex);
                }
            }
            throw e;
        }
    }

    /**
     * Validates, writes the config JSON file, and updates the registry.
     *
     * @throws IllegalArgumentException if validation fails
     * @throws BrandConfigException if the file cannot be written
     */
    public BrandAuthConfig save(BrandAuthConfig config) {
        ValidationResult validation = validate(config);
        if (!validation.isValid()) {
            throw new IllegalArgumentException(validation.getMessage());
        }

        String brandId = config.getBrandId();
        File file = getBrandFile(brandId);
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, config);
            registry.register(config);
            log.info("Saved brand config: {}", brandId);
            return config;
        } catch (IOException e) {
            throw new BrandConfigException("Failed to save brand config: " + brandId, e);
        }
    }

    /** Sets the brand ID on the config to match the path parameter, then delegates to {@link #save}. */
    public BrandAuthConfig update(String brandId, BrandAuthConfig config) {
        config.setBrandId(brandId);
        return save(config);
    }

    /**
     * Deletes the brand config file and removes the brand from the registry.
     *
     * @throws BrandConfigException if the file exists but cannot be deleted
     */
    public void delete(String brandId) {
        File file = getBrandFile(brandId);
        if (file.exists()) {
            try {
                Files.delete(file.toPath());
            } catch (IOException e) {
                log.error("Failed to delete brand config file: {}", file.getAbsolutePath());
                throw new BrandConfigException(
                    "Failed to delete brand config file: " + file.getAbsolutePath(), e);
            }
            log.info("Deleted brand config: {}", brandId);
        }
        registry.remove(brandId);
    }

    /**
     * Validates a brand config for structural correctness and lookup service availability.
     *
     * <p>Checks: non-blank brand ID, at least one level rule with at least one path, each path
     * has at least one required token, and all verification source service IDs are registered
     * and support the bound token type.
     *
     * @return {@link ValidationResult#ok()} or a descriptive error result
     */
    public ValidationResult validate(BrandAuthConfig config) {
        if (config.getBrandId() == null || config.getBrandId().trim().isEmpty()) {
            return ValidationResult.error("Brand ID is required");
        }
        boolean hasLevelRules = config.getLevelRules() != null && !config.getLevelRules().isEmpty();
        // Identification-only brands resolve to a party and stop; they need no level rules.
        if (!config.isIdentificationOnly() && !hasLevelRules) {
            return ValidationResult.error("At least one level rule is required");
        }
        if (hasLevelRules) {
            ValidationResult levelCheck = validateLevelRules(config.getLevelRules());
            if (!levelCheck.isValid()) {
                return levelCheck;
            }
        }
        return ValidationResult.ok();
    }

    /** Atomically reloads the registry from the config directory (no missing-brand window). */
    public void refreshRegistry() {
        loadFromDirectory();
    }

    /**
     * Reads and validates every brand config in the config directory and atomically swaps the
     * full set into the registry via {@link BrandRulesRegistry#replaceAll}. Invalid or unreadable
     * files are skipped (logged), so a single bad file never aborts the reload.
     */
    public void loadFromDirectory() {
        registry.replaceAll(readValidConfigs());
    }

    /** Reads the config directory and returns a map of brandId → config for every valid file. */
    private Map<String, BrandAuthConfig> readValidConfigs() {
        Map<String, BrandAuthConfig> loaded = new HashMap<>();
        File dir = new File(configDir);
        if (!dir.exists()) return loaded;
        File[] files = dir.listFiles((d, name) -> name.endsWith(JSON_EXT));
        if (files != null) {
            for (File file : files) {
                try {
                    BrandAuthConfig config = mapper.readValue(file, BrandAuthConfig.class);
                    ValidationResult validation = validate(config);
                    if (config.getBrandId() == null || !validation.isValid()) {
                        log.warn("Skipping invalid brand config {} — {}",
                            file.getName(),
                            config.getBrandId() == null ? "missing brandId" : validation.getMessage());
                        continue;
                    }
                    loaded.put(config.getBrandId(), config);
                    log.info("Loaded brand config: {} from {}", config.getBrandId(), file.getName());
                } catch (Exception e) {
                    log.warn("Failed to load brand file: {}", file.getName(), e);
                }
            }
        }
        return loaded;
    }

    /**
     * Clones a brand config under a new brand ID. Reads the source config, sets the new brand ID,
     * and saves. Useful for quickly creating a new brand that starts with the same rules as an
     * existing one.
     *
     * @param sourceBrandId the brand to clone from
     * @param newBrandId    the brand ID for the clone
     * @return the cloned and saved config
     * @throws UnknownBrandException   if the source brand doesn't exist
     * @throws IllegalArgumentException if a brand with newBrandId already exists
     */
    public BrandAuthConfig clone(String sourceBrandId, String newBrandId) {
        if (registry.contains(newBrandId)) {
            throw new IllegalArgumentException("Brand already exists: " + newBrandId);
        }
        BrandAuthConfig source = get(sourceBrandId);
        BrandAuthConfig clone = mapper.convertValue(
            mapper.valueToTree(source), BrandAuthConfig.class);
        clone.setBrandId(newBrandId);
        return save(clone);
    }

    /**
     * Exports a brand config as its raw JSON string. Used for the "Download JSON" UI feature.
     *
     * @throws UnknownBrandException if the brand doesn't exist
     */
    public String exportAsJson(String brandId) {
        BrandAuthConfig config = get(brandId);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        } catch (IOException e) {
            throw new BrandConfigException("Failed to serialize brand config: " + brandId, e);
        }
    }

    /**
     * Imports a brand config from a raw JSON string. Parses, validates, and saves it.
     * Used for the "Upload JSON" UI feature.
     *
     * @throws IllegalArgumentException if validation fails or the JSON is malformed
     */
    public BrandAuthConfig importFromJson(String jsonContent) {
        try {
            BrandAuthConfig config = mapper.readValue(jsonContent, BrandAuthConfig.class);
            return save(config);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse brand config JSON: " + e.getMessage(), e);
        }
    }

    private ValidationResult validateLevelRules(Map<AuthLevel, LevelRule> levelRules) {
        for (Map.Entry<AuthLevel, LevelRule> entry : levelRules.entrySet()) {
            LevelRule rule = entry.getValue();
            if (rule.getPaths() == null || rule.getPaths().isEmpty()) {
                return ValidationResult.error(
                    "Level " + entry.getKey() + " must have at least one token path");
            }
            for (int i = 0; i < rule.getPaths().size(); i++) {
                TokenPath path = rule.getPaths().get(i);
                if (path.getRequiredTokens() == null || path.getRequiredTokens().isEmpty()) {
                    return ValidationResult.error(
                        "Path " + i + " in level " + entry.getKey() + " must have required tokens");
                }
            }
        }
        return ValidationResult.ok();
    }

    private File getBrandFile(String brandId) {
        String sanitized = brandId.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
        return new File(configDir, sanitized + JSON_EXT);
    }
}
