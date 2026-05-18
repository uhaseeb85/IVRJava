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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    public TransferPolicy get(String sourceSystemId) {
        return policies.get(sourceSystemId);
    }

    public boolean isTokenHonored(String sourceSystemId, TokenType tokenType) {
        TransferPolicy policy = get(sourceSystemId);
        if (policy == null || !policy.isEnabled()) return false;
        return policy.getHonoredTokens() != null && policy.getHonoredTokens().contains(tokenType);
    }

    public AuthLevel getMaxHonoredLevel(String sourceSystemId) {
        TransferPolicy policy = get(sourceSystemId);
        if (policy == null || !policy.isEnabled()) return AuthLevel.NONE;
        return policy.getMaxHonoredLevel() != null ? policy.getMaxHonoredLevel() : AuthLevel.NONE;
    }

    public List<TokenType> getHonoredTokens(String sourceSystemId) {
        TransferPolicy policy = get(sourceSystemId);
        if (policy == null || !policy.isEnabled()) return Collections.emptyList();
        return policy.getHonoredTokens() != null ? policy.getHonoredTokens() : Collections.emptyList();
    }
}
