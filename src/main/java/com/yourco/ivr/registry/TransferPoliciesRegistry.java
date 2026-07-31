package com.yourco.ivr.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.TransferPoliciesConfig;
import com.yourco.ivr.domain.config.TransferPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of {@link TransferPolicy} objects loaded from
 * {@code ./config/transfers/*.json} at startup.
 *
 * <p>Transfer policies govern what authentication credit an incoming call transfer can carry
 * from another IVR system. When a call is transferred, the engine:
 * <ol>
 *   <li>Looks up the source system's policy by {@code sourceSystemId}.</li>
 *   <li>Filters the presented validated tokens to only those listed in
 *       {@link TransferPolicy#getHonoredTokens()}.</li>
 *   <li>Caps the transferred auth level at {@link TransferPolicy#getMaxHonoredLevel()}.</li>
 * </ol>
 *
 * <p>Unlike brand configs, transfer policies are loaded once at startup and require a
 * restart to pick up file changes ({@link #loadPolicies()} can also be called programmatically).
 */
@Component
public class TransferPoliciesRegistry {

    private static final Logger log = LoggerFactory.getLogger(TransferPoliciesRegistry.class);

    private final Map<String, TransferPolicy> policies = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final String configDir;

    public TransferPoliciesRegistry(ObjectMapper mapper,
                                    @Value("${ivr.transfer.config-dir:./config/transfers}") String configDir) {
        this.mapper = mapper;
        this.configDir = configDir;
    }

    @PostConstruct
    public void init() {
        File dir = new File(configDir);
        if (!dir.exists()) {
            dir.mkdirs();
            log.info("Created transfer config directory: {}", configDir);
        }
        loadPolicies();
    }

    public void loadPolicies() {
        policies.clear();
        File dir = new File(configDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                try {
                    TransferPoliciesConfig config = mapper.readValue(file, TransferPoliciesConfig.class);
                    if (config.getPolicies() != null) {
                        for (TransferPolicy policy : config.getPolicies()) {
                            policies.put(policy.getSourceSystemId(), policy);
                            log.info("Loaded transfer policy: {} (honoredTokens={}, maxLevel={}, enabled={})",
                                policy.getSourceSystemId(), policy.getHonoredTokens(),
                                policy.getMaxHonoredLevel(), policy.isEnabled());
                        }
                    }
                } catch (IOException e) {
                    log.warn("Failed to load transfer config: {}", file.getName(), e);
                }
            }
        }
    }

    /** Returns all loaded policies as a list. */
    public List<TransferPolicy> listAll() {
        return new ArrayList<>(policies.values());
    }

    /**
     * Persists all policies to the JSON config file and reloads the in-memory registry.
     * This is the single mutation entry point — never mutate {@link #policies} directly.
     *
     * @param allPolicies the complete set of policies to persist
     */
    public void saveAll(List<TransferPolicy> allPolicies) {
        TransferPoliciesConfig wrapper = new TransferPoliciesConfig();
        wrapper.setPolicies(allPolicies);
        try {
            File dir = new File(configDir);
            if (!dir.exists()) dir.mkdirs();
            // Find the first .json file in the config dir, or use a default name
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            File target = (files != null && files.length > 0)
                ? files[0]
                : new File(configDir, "transfer-policies.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(target, wrapper);
            // Reload in-memory map
            policies.clear();
            if (allPolicies != null) {
                for (TransferPolicy p : allPolicies) {
                    if (p.getSourceSystemId() != null) {
                        policies.put(p.getSourceSystemId(), p);
                    }
                }
            }
            log.info("Saved {} transfer policy(ies) to {}", allPolicies != null ? allPolicies.size() : 0, target.getName());
        } catch (IOException e) {
            throw new RuntimeException("Failed to save transfer policies", e);
        }
    }

    /**
     * Returns the policy for the given source system ID, or {@code null} if not configured.
     * Callers should treat a {@code null} return as an unconfigured/disallowed source.
     */
    public TransferPolicy get(String sourceSystemId) {
        return policies.get(sourceSystemId);
    }

    /**
     * Returns {@code true} if the policy for {@code sourceSystemId} is enabled and includes
     * {@code tokenType} in its honored-tokens list.
     */
    public boolean isTokenHonored(String sourceSystemId, TokenType tokenType) {
        TransferPolicy policy = get(sourceSystemId);
        if (policy == null || !policy.isEnabled()) return false;
        return policy.getHonoredTokens() != null && policy.getHonoredTokens().contains(tokenType);
    }

    /**
     * Returns the maximum auth level the policy allows to be transferred from
     * {@code sourceSystemId}; falls back to {@link AuthLevel#NONE} if the policy is absent,
     * disabled, or has no explicit cap.
     */
    public AuthLevel getMaxHonoredLevel(String sourceSystemId) {
        TransferPolicy policy = get(sourceSystemId);
        if (policy == null || !policy.isEnabled()) return AuthLevel.NONE;
        return policy.getMaxHonoredLevel() != null ? policy.getMaxHonoredLevel() : AuthLevel.NONE;
    }

}
