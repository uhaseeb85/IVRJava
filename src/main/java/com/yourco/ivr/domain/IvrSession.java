package com.yourco.ivr.domain;

import lombok.Data;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Data
public class IvrSession {
    private String sessionId;
    private String brandId;
    private String callerId;
    private AuthLevel currentLevel;
    private AuthLevel targetLevel;
    private SessionStatus status;

    private Map<TokenType, String> collectedTokens;
    private Set<TokenType> validatedTokens;
    private Map<TokenType, Integer> attemptCounts;
    private Map<AuthLevel, Integer> activePathIndexByLevel;
    private Map<TokenType, CrossBrandTokenRecord> crossBrandTokens;

    private Instant createdAt;
    private Instant lastActivityAt;

    public IvrSession() {
        this.currentLevel = AuthLevel.NONE;
        this.status = SessionStatus.COLLECTING;
        this.collectedTokens = new EnumMap<>(TokenType.class);
        this.validatedTokens = EnumSet.noneOf(TokenType.class);
        this.attemptCounts = new EnumMap<>(TokenType.class);
        this.activePathIndexByLevel = new EnumMap<>(AuthLevel.class);
        this.crossBrandTokens = new EnumMap<>(TokenType.class);
    }
}
