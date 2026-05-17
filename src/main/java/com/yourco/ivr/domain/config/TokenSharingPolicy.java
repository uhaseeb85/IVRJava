package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.TokenType;
import lombok.Data;

import java.util.Map;
import java.util.Set;

@Data
public class TokenSharingPolicy {
    private Set<TokenType> globallySharedTokens;
    private Map<TokenType, Set<String>> conditionallySharedFrom;
    private int crossBrandTokenMaxAgeSeconds;
}