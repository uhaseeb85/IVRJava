package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.TokenType;
import lombok.Data;

import java.util.List;

@Data
public class TokenPath {
    private int pathIndex;
    private String description;
    private List<TokenType> requiredTokens;
}