package com.yourco.ivr.service;

import com.yourco.ivr.api.dto.SessionResponse;
import com.yourco.ivr.api.dto.StartSessionRequest;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.engine.AuthEngine;
import com.yourco.ivr.registry.BrandRulesRegistry;
import com.yourco.ivr.repository.SessionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class SessionService {

    private final AuthEngine engine;
    private final SessionRepository sessionRepo;
    private final BrandRulesRegistry rulesRegistry;

    public SessionService(AuthEngine engine, SessionRepository sessionRepo, BrandRulesRegistry rulesRegistry) {
        this.engine = engine;
        this.sessionRepo = sessionRepo;
        this.rulesRegistry = rulesRegistry;
    }

    public SessionResponse start(StartSessionRequest req) {
        BrandAuthConfig config = rulesRegistry.get(req.getBrandId());

        IvrSession session = new IvrSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setBrandId(req.getBrandId());
        session.setCallerId(req.getCallerId());
        session.setCurrentLevel(AuthLevel.NONE);
        session.setTargetLevel(req.getTargetLevel());
        session.setStatus(SessionStatus.COLLECTING);
        session.setCreatedAt(Instant.now());
        session.setLastActivityAt(Instant.now());

        if (req.getCrossBrandTokens() != null) {
            session.getCrossBrandTokens().putAll(req.getCrossBrandTokens());
        }

        sessionRepo.save(session);

        // Evaluate immediately — cross-brand tokens may already satisfy some levels
        return engine.evaluateProgress(session, config);
    }

    public SessionResponse submitToken(String sessionId, TokenType tokenType, String tokenValue) {
        return engine.submitToken(sessionId, tokenType, tokenValue);
    }

    public SessionResponse escalate(String sessionId, AuthLevel targetLevel) {
        return engine.escalate(sessionId, targetLevel);
    }

    public SessionResponse getStatus(String sessionId) {
        IvrSession session = sessionRepo.getOrThrow(sessionId);
        return SessionResponse.fromSession(session);
    }

    public void end(String sessionId) {
        sessionRepo.delete(sessionId);
    }
}