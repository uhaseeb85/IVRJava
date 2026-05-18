package com.yourco.ivr.service;

import com.yourco.ivr.api.dto.CallTransferRequest;
import com.yourco.ivr.api.dto.SessionResponse;
import com.yourco.ivr.api.dto.StartSessionRequest;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.engine.AuthEngine;
import com.yourco.ivr.exception.TransferNotAllowedException;
import com.yourco.ivr.registry.BrandRulesRegistry;
import com.yourco.ivr.registry.TransferPoliciesRegistry;
import com.yourco.ivr.repository.SessionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SessionService {

    private final AuthEngine engine;
    private final SessionRepository sessionRepo;
    private final BrandRulesRegistry rulesRegistry;
    private final TransferPoliciesRegistry transferRegistry;

    public SessionService(AuthEngine engine, SessionRepository sessionRepo,
                          BrandRulesRegistry rulesRegistry,
                          TransferPoliciesRegistry transferRegistry) {
        this.engine = engine;
        this.sessionRepo = sessionRepo;
        this.rulesRegistry = rulesRegistry;
        this.transferRegistry = transferRegistry;
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

        // Process any initial tokens provided at session start
        if (req.getInitialTokens() != null && !req.getInitialTokens().isEmpty()) {
            for (Map.Entry<TokenType, String> entry : req.getInitialTokens().entrySet()) {
                SessionResponse tokenResponse = engine.submitToken(
                    session.getSessionId(), entry.getKey(), entry.getValue());
                if (tokenResponse.getStatus() == SessionStatus.FAILED) {
                    return tokenResponse;
                }
            }
            // Re-fetch session after token processing and re-evaluate
            IvrSession updatedSession = sessionRepo.getOrThrow(session.getSessionId());
            return engine.evaluateProgress(updatedSession, config);
        }

        // Evaluate immediately — cross-brand tokens may already satisfy some levels
        return engine.evaluateProgress(session, config);
    }

    public SessionResponse transfer(CallTransferRequest req) {
        // 1. Verify source system is known and enabled
        if (transferRegistry.get(req.getSourceSystemId()) == null) {
            throw new TransferNotAllowedException(
                "Source system not configured: " + req.getSourceSystemId());
        }
        if (!transferRegistry.get(req.getSourceSystemId()).isEnabled()) {
            throw new TransferNotAllowedException(
                "Source system is disabled: " + req.getSourceSystemId());
        }

        // 2. Get target brand config
        BrandAuthConfig config = rulesRegistry.get(req.getBrandId());

        // 3. Filter validated tokens to only those honored by the policy
        List<TokenType> honoredTokens = new ArrayList<>();
        if (req.getValidatedTokens() != null) {
            for (TokenType tokenType : req.getValidatedTokens()) {
                if (transferRegistry.isTokenHonored(req.getSourceSystemId(), tokenType)) {
                    honoredTokens.add(tokenType);
                }
            }
        }

        // 4. Cap currentLevel at the policy's maxHonoredLevel
        AuthLevel transferredLevel = req.getCurrentLevel() != null ? req.getCurrentLevel() : AuthLevel.NONE;
        AuthLevel maxHonored = transferRegistry.getMaxHonoredLevel(req.getSourceSystemId());
        if (transferredLevel.getRank() > maxHonored.getRank()) {
            transferredLevel = maxHonored;
        }

        // 5. Create session
        IvrSession session = new IvrSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setBrandId(req.getBrandId());
        session.setCallerId(req.getCallerId());
        session.setCurrentLevel(transferredLevel);
        session.setTargetLevel(req.getTargetLevel());
        session.setStatus(SessionStatus.COLLECTING);
        session.setTransferredFrom(req.getSourceSystemId());
        session.setCreatedAt(Instant.now());
        session.setLastActivityAt(Instant.now());

        // 6. Hand off to engine to populate tokens and evaluate
        return engine.transferSession(session, config, honoredTokens, req.getSourceSystemId());
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