package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.AuthLevel;
import lombok.Data;

import java.util.Map;

@Data
public class BrandAuthConfig {
    private String brandId;
    private Map<AuthLevel, LevelRule> levelRules;
    private DisambiguationConfig disambiguation;

    public DisambiguationConfig getDisambiguation() {
        if (disambiguation == null) {
            disambiguation = new DisambiguationConfig();
        }
        return disambiguation;
    }
}