package com.yourco.ivr.service;

import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.api.dto.CallTransferRequest;
import com.yourco.ivr.api.dto.StartAuthenticateRequest;
import com.yourco.ivr.domain.*;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.RiskPolicy;
import com.yourco.ivr.domain.config.TransferPolicy;
import com.yourco.ivr.engine.AuthEngine;
import com.yourco.ivr.engine.DisambiguationEngine;
import com.yourco.ivr.exception.HighRiskCallerException;
import com.yourco.ivr.exception.TransferNotAllowedException;
import com.yourco.ivr.exception.UnknownCallerException;
import com.yourco.ivr.partylookup.PartyLookupProvider;
import com.yourco.ivr.partyrisk.PhoneRiskProvider;
import com.yourco.ivr.preference.CustomerPreferenceProvider;
import com.yourco.ivr.registry.BrandRulesRegistry;
import com.yourco.ivr.registry.TransferPoliciesRegistry;
import com.yourco.ivr.repository.SessionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final PhoneRiskProvider riskProvider;

    public AuthenticateService(AuthEngine engine, SessionRepository sessionRepo,
                               BrandRulesRegistry rulesRegistry,
                               TransferPoliciesRegistry transferRegistry,
                               PartyLookupProvider partyLookup,
                               CustomerPreferenceProvider preferenceProvider,
                               DisambiguationEngine disambiguationEngine,
                               PhoneRiskProvider riskProvider) {
        this.engine = engine;
        this.sessionRepo = sessionRepo;
        this.rulesRegistry = rulesRegistry;
        this.transferRegistry = transferRegistry;
        this.partyLookup = partyLookup;
        this.preferenceProvider = preferenceProvider;
        this.disambiguationEngine = disambiguationEngine;
        this.riskProvider = riskProvider;
    }

    public AuthenticateResponse start(StartAuthenticateRequest req) {
        BrandAuthConfig config = rulesRegistry.get(req.getBrandId());

        // ── Risk assessment ──────────────────────────────────────────────────
        // Assess caller risk before creating a session so CRITICAL callers are
        // rejected without any session state being written to the DB.
        RiskAssessment risk = riskProvider.assess(req.getCallerId(), req.getBrandId());
        RiskPolicy riskPolicy = (config.getRiskPolicies() != null)
            ? config.getRiskPolicies().get(risk.getLevel()) : null;

        if (riskPolicy != null && riskPolicy.isReject()) {
            throw new HighRiskCallerException(req.getCallerId(), risk.getLevel());
        }

        // Determine effective target level (risk policy may force it higher)
        AuthLevel effectiveTarget = req.getTargetLevel();
        if (riskPolicy != null && riskPolicy.getMinimumTargetLevel() != null
                && riskPolicy.getMinimumTargetLevel().getRank() > effectiveTarget.getRank()) {
            effectiveTarget = riskPolicy.getMinimumTargetLevel();
        }

        // ── Session creation ─────────────────────────────────────────────────
        IvrSession session = new IvrSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setBrandId(req.getBrandId());
        session.setCallerId(req.getCallerId());
        session.setCurrentLevel(AuthLevel.NONE);
        session.setTargetLevel(effectiveTarget);
        session.setRiskAssessment(risk);
        session.setStatus(SessionStatus.COLLECTING);
        session.setCreatedAt(Instant.now());
        session.setLastActivityAt(Instant.now());

        // ── Party lookup and disambiguation (always-on) ──────────────────────
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

        // Single party — load preferences and merge risk-blocked tokens
        CustomerPreference prefs = preferenceProvider.getPreferences(
            parties.get(0).getPartyId(), session.getBrandId());
        mergeRiskBlockedTokens(prefs, riskPolicy);
        session.setMatchedParty(parties.get(0));
        session.setCustomerPreferences(prefs);
        sessionRepo.save(session);

        // Process any initial tokens provided at session start
        if (req.getInitialTokens() != null && !req.getInitialTokens().isEmpty()) {
            for (Map.Entry<TokenType, String> entry : req.getInitialTokens().entrySet()) {
                AuthenticateResponse tokenResponse = engine.submitToken(
                    session.getSessionId(), entry.getKey(), entry.getValue());
                if (tokenResponse.getStatus() == SessionStatus.FAILED
                        || tokenResponse.getStatus() == SessionStatus.LOCKED) {
                    return tokenResponse;
                }
            }
            IvrSession updatedSession = sessionRepo.getOrThrow(session.getSessionId());
            return engine.evaluateProgress(updatedSession, config);
        }

        return engine.evaluateProgress(session, config);
    }

    public AuthenticateResponse transfer(CallTransferRequest req) {
        TransferPolicy policy = transferRegistry.get(req.getSourceSystemId());
        if (policy == null) {
            throw new TransferNotAllowedException(
                "Source system not configured: " + req.getSourceSystemId());
        }
        if (!policy.isEnabled()) {
            throw new TransferNotAllowedException(
                "Source system is disabled: " + req.getSourceSystemId());
        }

        // 2. Get target brand config
        BrandAuthConfig config = rulesRegistry.get(req.getBrandId());

        // 3. Risk assessment — apply same gate as session start
        RiskAssessment risk = riskProvider.assess(req.getCallerId(), req.getBrandId());
        RiskPolicy riskPolicy = (config.getRiskPolicies() != null)
            ? config.getRiskPolicies().get(risk.getLevel()) : null;
        if (riskPolicy != null && riskPolicy.isReject()) {
            throw new HighRiskCallerException(req.getCallerId(), risk.getLevel());
        }

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

        // Apply minimumTargetLevel override for transferred sessions too
        AuthLevel transferTarget = req.getTargetLevel();
        if (riskPolicy != null && riskPolicy.getMinimumTargetLevel() != null
                && riskPolicy.getMinimumTargetLevel().getRank() > transferTarget.getRank()) {
            transferTarget = riskPolicy.getMinimumTargetLevel();
        }

        // 5. Create session
        IvrSession session = new IvrSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setBrandId(req.getBrandId());
        session.setCallerId(req.getCallerId());
        session.setCurrentLevel(transferredLevel);
        session.setTargetLevel(transferTarget);
        session.setRiskAssessment(risk);
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

    public AuthenticateResponse submitTokenWithCaller(String sessionId, TokenType tokenType,
                                                       String tokenValue, String callerId) {
        return engine.submitTokenWithCaller(sessionId, tokenType, tokenValue, callerId);
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

    /**
     * Merges the risk policy's blocked tokens into the customer preference object
     * in place. Safe to call when either argument is null or has no blocked tokens.
     */
    private static void mergeRiskBlockedTokens(CustomerPreference prefs, RiskPolicy riskPolicy) {
        if (riskPolicy == null
                || riskPolicy.getBlockedTokens() == null
                || riskPolicy.getBlockedTokens().isEmpty()) {
            return;
        }
        Set<TokenType> merged = new HashSet<>();
        if (prefs.getBlockedTokens() != null) {
            merged.addAll(prefs.getBlockedTokens());
        }
        merged.addAll(riskPolicy.getBlockedTokens());
        prefs.setBlockedTokens(merged);
    }
}
