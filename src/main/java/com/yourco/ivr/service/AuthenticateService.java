package com.yourco.ivr.service;

import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.api.dto.CallTransferRequest;
import com.yourco.ivr.api.dto.StartAuthenticateRequest;
import com.yourco.ivr.domain.*;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.engine.AuthEngine;
import com.yourco.ivr.engine.DisambiguationEngine;
import com.yourco.ivr.exception.TransferNotAllowedException;
import com.yourco.ivr.exception.UnknownCallerException;
import com.yourco.ivr.partylookup.PartyLookupProvider;
import com.yourco.ivr.preference.CustomerPreferenceProvider;
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
public class AuthenticateService {

    private final AuthEngine engine;
    private final SessionRepository sessionRepo;
    private final BrandRulesRegistry rulesRegistry;
    private final TransferPoliciesRegistry transferRegistry;
    private final PartyLookupProvider partyLookup;
    private final CustomerPreferenceProvider preferenceProvider;
    private final DisambiguationEngine disambiguationEngine;

    public AuthenticateService(AuthEngine engine, SessionRepository sessionRepo,
                               BrandRulesRegistry rulesRegistry,
                               TransferPoliciesRegistry transferRegistry,
                               PartyLookupProvider partyLookup,
                               CustomerPreferenceProvider preferenceProvider,
                               DisambiguationEngine disambiguationEngine) {
        this.engine = engine;
        this.sessionRepo = sessionRepo;
        this.rulesRegistry = rulesRegistry;
        this.transferRegistry = transferRegistry;
        this.partyLookup = partyLookup;
        this.preferenceProvider = preferenceProvider;
        this.disambiguationEngine = disambiguationEngine;
    }

    public AuthenticateResponse start(StartAuthenticateRequest req) {
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

        // Party lookup and disambiguation (always-on)
        List<Party> parties = partyLookup.lookupByAni(req.getCallerId());

        if (parties.isEmpty()) {
            throw new UnknownCallerException(req.getCallerId());
        }

        session.setPhase(parties.size() > 1
            ? SessionPhase.DISAMBIGUATION : SessionPhase.AUTHENTICATING);
        session.setCandidateParties(parties);
        sessionRepo.save(session);

        if (parties.size() > 1) {
            AuthenticateResponse disResp = disambiguationEngine.start(
                session, config.getDisambiguation());
            if (session.getPhase() == SessionPhase.AUTHENTICATING) {
                return engine.evaluateProgress(session, config);
            }
            return disResp;
        }

        CustomerPreference prefs = preferenceProvider.getPreferences(
            parties.get(0).getPartyId(), session.getBrandId());
        session.setMatchedParty(parties.get(0));
        session.setCustomerPreferences(prefs);
        sessionRepo.save(session);

        // Process any initial tokens provided at session start
        if (req.getInitialTokens() != null && !req.getInitialTokens().isEmpty()) {
            for (Map.Entry<TokenType, String> entry : req.getInitialTokens().entrySet()) {
                AuthenticateResponse tokenResponse = engine.submitToken(
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

    public AuthenticateResponse transfer(CallTransferRequest req) {
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

    public AuthenticateResponse submitToken(String sessionId, TokenType tokenType, String tokenValue) {
        return engine.submitToken(sessionId, tokenType, tokenValue);
    }

    public AuthenticateResponse escalate(String sessionId, AuthLevel targetLevel) {
        return engine.escalate(sessionId, targetLevel);
    }

    public AuthenticateResponse getStatus(String sessionId) {
        IvrSession session = sessionRepo.getOrThrow(sessionId);
        return AuthenticateResponse.fromSession(session);
    }

    public void end(String sessionId) {
        sessionRepo.delete(sessionId);
    }
}
