package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.AuthLevel;
import lombok.Data;

import java.util.Map;

@Data
public class BrandAuthConfig {
    private String brandId;
    private Map<AuthLevel, LevelRule> levelRules;
}