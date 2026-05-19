package com.yourco.ivr.domain.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DisambiguationConfig {
    private int maxDisambiguationTokens;
    private List<DisambiguationRuleConfig> rules;

    public DisambiguationConfig() {
        this.rules = new ArrayList<>();
        this.maxDisambiguationTokens = 3;
    }

    @Data
    public static class DisambiguationRuleConfig {
        private String type;
    }
}
