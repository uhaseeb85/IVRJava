package com.yourco.ivr.domain.config;

import lombok.Data;

import java.util.List;

@Data
public class LevelRule {
    private List<TokenPath> paths;
    private int maxRetriesPerToken;
    private int lockoutSeconds;
}