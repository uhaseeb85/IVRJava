package com.yourco.ivr.engine;

import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.api.dto.ProcessingEvent;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.SessionPhase;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.LevelRule;
import com.yourco.ivr.domain.config.TokenPath;
import com.yourco.ivr.exception.BrandConfigException;
import com.yourco.ivr.exception.SessionLockedException;
import com.yourco.ivr.exception.SessionNotFoundException;
import com.yourco.ivr.engine.path.ActivePath;
import com.yourco.ivr.engine.path.ActivePathManager;
import com.yourco.ivr.engine.preference.PreferenceFilter;
import com.yourco.ivr.engine.slot.TokenSlotResolver;
import com.yourco.ivr.engine.validation.ExternalValidator;
import com.yourco.ivr.registry.BrandRulesRegistry;
import com.yourco.ivr.repository.SessionRepository;
import com.yourco.ivr.validator.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.yourco.ivr.engine.response.ProcessingLog.add;
import static com.yourco.ivr.engine.response.ResponseAssembler.*;

/**
 * Core authentication state machine — the heart of the IVR auth engine.
 */
@Service
public class AuthEngine {

    private static final Logger log = LoggerFactory.getLogger(AuthEngine.class);

    private final BrandRulesRegistry rulesRegistry;
    private final SessionRepository sessionRepo;
    private final PromptResolver promptResolver;
    private final DisambiguationEngine disambiguationEngine;
    private final ActivePathManager pathManager;
    private final TokenSlotResolver slotResolver;
    private final ExternalValidator externalValidator;
    private final AttemptCoordinator attemptCoordinator;

    public AuthEngine(BrandRulesRegistry rulesRegistry,
                      SessionRepository sessionRepo,
                      PromptResolver promptResolver,
                      DisambiguationEngine disambiguationEngine,
                      ActivePathManager pathManager,
                      TokenSlotResolver slotResolver,
                      ExternalValidator externalValidator,
                      AttemptCoordinator attemptCoordinator) {
        this.rulesRegistry = rulesRegistry;
        this.sessionRepo = sessionRepo;
        this.promptResolver = promptResolver;
        this.disambiguationEngine = disambiguationEngine;
        this.pathManager = pathManager;
        this.slotResolver = slotResolver;
        this.externalValidator = externalValidator;
        this.attemptCoordinator = attemptCoordinator;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public AuthenticateResponse submitToken(String sessionId,
                                            TokenType tokenType,
                                            String tokenValue) {
        return submitTokenWithCaller(sessionId, tokenType, tokenValue, null);
    }

    public AuthenticateResponse submitTokenWithCaller(String sessionId,
                                                      TokenType tokenType,
                                                      String tokenValue,
                                                      String callerId) {
        IvrSession session = sessionRepo.getOrThrow(sessionId);
        resetExpiredRedirectOrThrow(session);
        verifyCallerOwnership(session, callerId);

        if (isTerminal(session)) {
            return AuthenticateResponse.fromSession(session);
        }

        BrandAuthConfig config = rulesRegistry.get(session.getBrandId());
        session.getCollectedTokens().put(tokenType, tokenValue);

        if (session.getPhase() == SessionPhase.DISAMBIGUATION) {
            return handleDisambiguationPhase(session, config, tokenType, tokenValue);
        }

        return processTokenInAuthPhase(session, config, tokenType, tokenValue);
    }

    public AuthenticateResponse transferSession(IvrSession session,
                                                BrandAuthConfig config,
                                                List<TokenType> validatedTokens) {
        for (TokenType tokenType : validatedTokens) {
            session.getValidatedTokens().add(tokenType);
        }
        sessionRepo.save(session);
        return onPartyResolved(session, config);
    }

    public AuthenticateResponse escalate(String sessionId, AuthLevel newTarget) {
        IvrSession session = sessionRepo.getOrThrow(sessionId);
        AuthLevel current = session.getCurrentLevel();

        BrandAuthConfig config = rulesRegistry.get(session.getBrandId());
        if (config.isIdentificationOnly()) {
            throw new IllegalArgumentException("Escalation is not supported for identification-only brands");
        }

        // Guard against escalating terminal sessions
        SessionStatus status = session.getStatus();
        if (status == SessionStatus.AUTHENTICATED
            || status == SessionStatus.FAILED
            || status == SessionStatus.REDIRECT_TO_AGENT) {
            return AuthenticateResponse.fromSession(session);
        }

        if (!newTarget.isHigherThan(current)) {
            throw new IllegalArgumentException("Target must exceed current level");
        }

        // Enforce customer's maximum allowed auth level
        if (session.getCustomerPreferences() != null
            && session.getCustomerPreferences().getMaxAllowedLevel() != null
            && newTarget.isHigherThan(session.getCustomerPreferences().getMaxAllowedLevel())) {
            throw new IllegalArgumentException(
                "Escalation to " + newTarget + " exceeds customer's maximum allowed level ("
                + session.getCustomerPreferences().getMaxAllowedLevel() + ")");
        }

        log.info("AUTH [{}] brand={} caller={} escalate {} -> {}",
            sessionId, session.getBrandId(), session.getCallerId(), current, newTarget);

        session.setTargetLevel(newTarget);
        sessionRepo.save(session);
        return evaluateProgress(session, config);
    }

    public AuthenticateResponse onPartyResolved(IvrSession session, BrandAuthConfig config) {
        if (config.isIdentificationOnly() && !hasNoneRule(config)) {
            return finalizeIdentification(session);
        }
        if (config.isIdentificationOnly()) {
            markCollectedTokensSatisfyingNoneRule(session, config, externalValidator);
        }
        return evaluateProgress(session, config);
    }

    /**
     * For identification-only brands with NONE-level rules: any already-collected token that a
     * NONE path requires is promoted to a validated token, so progress evaluation can confirm it.
     */
    private static void markCollectedTokensSatisfyingNoneRule(IvrSession session, BrandAuthConfig config,
                                                             ExternalValidator externalValidator) {
        LevelRule noneRule = config.getLevelRules().get(AuthLevel.NONE);
        for (TokenType collected : session.getCollectedTokens().keySet()) {
            if (isRequiredByAnyPath(noneRule, collected)) {
                ValidationResult vr = externalValidator.validate(session, collected,
                    session.getCollectedTokens().get(collected));
                if (vr.isValid()) {
                    session.getValidatedTokens().add(collected);
                }
            }
        }
    }

    private static boolean isRequiredByAnyPath(LevelRule rule, TokenType token) {
        for (TokenPath path : rule.getPaths()) {
            if (path.getRequiredTokens().contains(token)) {
                return true;
            }
        }
        return false;
    }

    public AuthenticateResponse evaluateProgress(IvrSession session, BrandAuthConfig config) {
        ActivePath active = pathManager.resolveActivePath(session, config);
        if (active.rule() == null) {
            throw new BrandConfigException("No rule defined for level: " + session.getTargetLevel()
                + " in brand " + session.getBrandId());
        }
        if (active.path() == null) {
            return failSession(session, "Authentication failed. All retry attempts exhausted.");
        }

        LevelRule rule = active.rule();
        TokenPath activePath = active.path();

        TokenType nextToken = TokenSlotResolver.findNextRequired(session.getValidatedTokens(), activePath);
        if (nextToken == null) {
            return completeAuthentication(session, config);
        }

        TokenType originalRequired = nextToken;
        if (PreferenceFilter.isBlocked(session, nextToken)) {
            nextToken = PreferenceFilter.findAlternativeToken(session, activePath, nextToken);
            if (nextToken == null) {
                return pathManager.advanceToNextPathOrFail(session, config, rule, active.index(),
                    this::evaluateProgress);
            }
        }

        session.setStatus(SessionStatus.COLLECTING);
        sessionRepo.save(session);

        List<TokenType> acceptedTokens = slotResolver.buildAcceptedTokens(session, activePath,
            nextToken, originalRequired, t -> PreferenceFilter.isBlocked(session, t));

        String prompt = promptResolver.resolvePrompt(nextToken, activePath, rule.getMaxRetriesFor(nextToken));
        return collecting(session, nextToken, acceptedTokens, rule.getMaxRetriesFor(nextToken), prompt);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private boolean isTerminal(IvrSession session) {
        return session.getStatus() == SessionStatus.AUTHENTICATED
            || session.getStatus() == SessionStatus.FAILED;
    }

    private AuthenticateResponse processTokenInAuthPhase(IvrSession session,
                                                         BrandAuthConfig config,
                                                         TokenType tokenType,
                                                         String tokenValue) {
        List<ProcessingEvent> procLog = new ArrayList<>();

        ActivePath active = pathManager.resolveActivePath(session, config);

        // Early guard: if all fallback paths are exhausted, fail immediately
        // without running validation or mutating state
        if (active.path() == null) {
            add(procLog, "WARN", "All authentication paths exhausted for level " + session.getTargetLevel());
            return failSession(session, "Authentication failed. All retry attempts exhausted.");
        }

        TokenType nextRequired = TokenSlotResolver.findNextRequired(session.getValidatedTokens(), active.path());
        List<TokenType> acceptedForSlot = slotResolver.buildAcceptedTokens(session, active.path(),
            nextRequired, nextRequired, t -> PreferenceFilter.isBlocked(session, t));

        logContext(procLog, session, active, nextRequired, acceptedForSlot);

        if (!acceptedForSlot.contains(tokenType)) {
            return attemptCoordinator.onWrongTypeFailure(session, config, nextRequired, procLog);
        }

        ValidationResult validationResult = externalValidator.validate(session, tokenType, tokenValue);
        boolean valid = validationResult.isValid();

        add(procLog, valid ? "PASS" : "FAIL",
            "External validation: " + tokenType + " → " + describeOutcome(validationResult));

        log.info("AUTH [{}] brand={} caller={} token={} result={}",
            session.getSessionId(), session.getBrandId(), session.getCallerId(), tokenType,
            valid ? "PASS" : "FAIL");

        if (!valid) {
            return attemptCoordinator.onValidationFailure(session, config, tokenType, procLog,
                this::evaluateProgress);
        }

        TokenType resolvedToken = slotResolver.resolveBackupToken(session, config, tokenType);
        add(procLog, "INFO",
            resolvedToken != tokenType
                ? "Backup resolution: " + tokenType + " satisfies required slot " + resolvedToken
                : tokenType + " is a direct required token (no backup mapping)");

        session.getValidatedTokens().add(resolvedToken);
        session.getAttemptCounts().remove(tokenType);
        if (resolvedToken != tokenType) {
            session.getAttemptCounts().remove(resolvedToken);
        }

        add(procLog, "INFO", "Validated tokens now: " + session.getValidatedTokens());

        AuthenticateResponse evalResp = evaluateProgress(session, config);
        if (evalResp.getStatus() == SessionStatus.AUTHENTICATED) {
            add(procLog, "PASS", "Path complete → " + session.getTargetLevel() + " achieved");
        } else {
            logNextRequired(procLog, evalResp);
        }
        evalResp.setProcessingLog(procLog);
        return evalResp;
    }

    private void resetExpiredRedirectOrThrow(IvrSession session) {
        if (session.getStatus() != SessionStatus.REDIRECT_TO_AGENT) return;
        if (session.getLockedUntil() == null || !Instant.now().isAfter(session.getLockedUntil())) {
            throw new SessionLockedException(session.getSessionId(), session.getLockedUntil());
        }
        session.setStatus(SessionStatus.COLLECTING);
        session.getAttemptCounts().clear();
        session.setLockedUntil(null);
        sessionRepo.save(session);
    }

    private static void verifyCallerOwnership(IvrSession session, String callerId) {
        if (callerId != null && !callerId.equals(session.getCallerId())) {
            log.warn("CallerId mismatch for session {}: expected {}, got {}",
                session.getSessionId(), session.getCallerId(), callerId);
            throw new SessionNotFoundException(session.getSessionId());
        }
    }

    private AuthenticateResponse handleDisambiguationPhase(IvrSession session,
                                                            BrandAuthConfig config,
                                                            TokenType tokenType,
                                                            String tokenValue) {
        AuthenticateResponse disResp = disambiguationEngine.handleToken(session, tokenType, tokenValue);
        if (session.getPhase() == SessionPhase.AUTHENTICATING) {
            log.info("AUTH [{}] brand={} caller={} disambiguation resolved party={}",
                session.getSessionId(), session.getBrandId(), session.getCallerId(),
                session.getMatchedParty() != null ? session.getMatchedParty().getPartyId() : "null");
            return onPartyResolved(session, config);
        }
        return disResp;
    }

    private AuthenticateResponse completeAuthentication(IvrSession session, BrandAuthConfig config) {
        if (config.isIdentificationOnly() && session.getTargetLevel() == AuthLevel.NONE) {
            return finalizeIdentification(session);
        }
        session.setCurrentLevel(session.getTargetLevel());
        session.setStatus(SessionStatus.AUTHENTICATED);
        sessionRepo.save(session);
        return authenticated(session, "Authentication successful. You are now at " + session.getCurrentLevel() + " level.");
    }

    private AuthenticateResponse finalizeIdentification(IvrSession session) {
        session.setCurrentLevel(AuthLevel.NONE);
        session.setTargetLevel(AuthLevel.NONE);
        session.setStatus(SessionStatus.AUTHENTICATED);
        sessionRepo.save(session);
        log.info("AUTH [{}] brand={} caller={} IDENTIFICATION_ONLY resolved party={}",
            session.getSessionId(), session.getBrandId(), session.getCallerId(),
            session.getMatchedParty() != null ? session.getMatchedParty().getPartyId() : "null");
        return authenticated(session, "Caller identified.");
    }

    private AuthenticateResponse failSession(IvrSession session, String message) {
        session.setStatus(SessionStatus.FAILED);
        sessionRepo.save(session);
        return failed(session, message);
    }

    private static boolean hasNoneRule(BrandAuthConfig config) {
        return config.getLevelRules() != null
            && config.getLevelRules().containsKey(AuthLevel.NONE);
    }

    // ── Static helpers ───────────────────────────────────────────────────────

    private static void logContext(List<ProcessingEvent> procLog, IvrSession session,
                                    ActivePath active, TokenType nextRequired,
                                    List<TokenType> acceptedForSlot) {
        add(procLog, "INFO",
            "Brand: " + session.getBrandId() + " | Target: " + session.getTargetLevel()
            + " | Phase: AUTHENTICATING");

        if (active.path() != null) {
            add(procLog, "INFO",
                "Active path: path" + active.index() + " → " + active.path().getRequiredTokens());
        }

        Set<TokenType> validatedSoFar = session.getValidatedTokens();
        add(procLog, "INFO",
            "Validated tokens: " + (validatedSoFar.isEmpty() ? "[none]" : validatedSoFar));

        if (nextRequired != null) {
            add(procLog, "INFO",
                "Collecting: " + nextRequired + " | Accepted alternatives: " + acceptedForSlot);
        }
    }

    private static void logNextRequired(List<ProcessingEvent> procLog, AuthenticateResponse resp) {
        if (resp.getNextRequiredToken() == null) return;
        add(procLog, "INFO", "Next required: " + resp.getNextRequiredToken());
        if (resp.getAcceptedTokens() != null && !resp.getAcceptedTokens().isEmpty()) {
            add(procLog, "INFO", "Accepted for next slot: " + resp.getAcceptedTokens());
        }
    }

    private static String describeOutcome(ValidationResult result) {
        if (result.isValid()) return "PASS";
        return result.getErrorCode() != null ? result.getErrorCode().name() : "FAIL";
    }
}