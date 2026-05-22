package com.yourco.ivr.engine;

import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.SessionPhase;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.CrossBrandTokenRecord;
import com.yourco.ivr.domain.CustomerPreference;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.DisambiguationConfig;
import com.yourco.ivr.domain.config.LevelRule;
import com.yourco.ivr.domain.config.TokenPath;
import com.yourco.ivr.exception.SessionLockedException;
import com.yourco.ivr.exception.SessionNotFoundException;
import com.yourco.ivr.registry.BrandRulesRegistry;
import com.yourco.ivr.repository.SessionRepository;
import com.yourco.ivr.validator.TokenValidationContext;
import com.yourco.ivr.validator.TokenValidator;
import com.yourco.ivr.validator.TokenValidatorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AuthEngine {

    private static final Logger log = LoggerFactory.getLogger(AuthEngine.class);

    private final BrandRulesRegistry rulesRegistry;
    private final TokenValidatorRegistry validatorRegistry;
    private final SessionRepository sessionRepo;
    private final CrossBrandTokenEvaluator crossBrandEvaluator;
    private final PromptResolver promptResolver;
    private final DisambiguationEngine disambiguationEngine;

    public AuthEngine(BrandRulesRegistry rulesRegistry,
                      TokenValidatorRegistry validatorRegistry,
                      SessionRepository sessionRepo,
                      CrossBrandTokenEvaluator crossBrandEvaluator,
                      PromptResolver promptResolver,
                      DisambiguationEngine disambiguationEngine) {
        this.rulesRegistry = rulesRegistry;
        this.validatorRegistry = validatorRegistry;
        this.sessionRepo = sessionRepo;
        this.crossBrandEvaluator = crossBrandEvaluator;
        this.promptResolver = promptResolver;
        this.disambiguationEngine = disambiguationEngine;
    }

    /**
     * Called when the IVR platform submits a token value.
     */
    public AuthenticateResponse submitToken(String sessionId,
                                        TokenType tokenType,
                                        String tokenValue) {
        return submitTokenWithCaller(sessionId, tokenType, tokenValue, null);
    }

    /**
     * Called when the IVR platform submits a token value with optional callerId
     * for session ownership validation.
     */
    public AuthenticateResponse submitTokenWithCaller(String sessionId,
                                        TokenType tokenType,
                                        String tokenValue,
                                        String callerId) {
        IvrSession session = sessionRepo.getOrThrow(sessionId);

        // Locked session guard
        if (session.getStatus() == SessionStatus.LOCKED) {
            if (session.getLockedUntil() != null
                    && Instant.now().isAfter(session.getLockedUntil())) {
                session.setStatus(SessionStatus.COLLECTING);
                session.getAttemptCounts().clear();
                session.setLockedUntil(null);
                sessionRepo.save(session);
            } else {
                throw new SessionLockedException(sessionId, session.getLockedUntil());
            }
        }

        // Optional session ownership validation
        if (callerId != null && !callerId.equals(session.getCallerId())) {
            log.warn("CallerId mismatch for session {}: expected {}, got {}",
                sessionId, session.getCallerId(), callerId);
            throw new SessionNotFoundException(sessionId);
        }

        BrandAuthConfig config = rulesRegistry.get(session.getBrandId());

        // Route to disambiguation if session is still resolving parties
        if (session.getPhase() == SessionPhase.DISAMBIGUATION) {
            DisambiguationConfig disConfig = config.getDisambiguation();
            AuthenticateResponse disResp = disambiguationEngine.handleToken(
                session, tokenType, tokenValue, disConfig);
            if (session.getPhase() == SessionPhase.AUTHENTICATING) {
                log.info("AUTH [{}] brand={} caller={} disambiguation resolved party={}",
                    sessionId, session.getBrandId(), session.getCallerId(),
                    session.getMatchedParty() != null ? session.getMatchedParty().getPartyId() : "null");
                return evaluateProgress(session, config);
            }
            return disResp;
        }

        // Store the collected token value
        session.getCollectedTokens().put(tokenType, tokenValue);

        // 1. Validate externally
        boolean valid = validateExternally(session, tokenType, tokenValue);

        log.info("AUTH [{}] brand={} caller={} token={} result={}",
            sessionId, session.getBrandId(), session.getCallerId(), tokenType,
            valid ? "PASS" : "FAIL");

        if (!valid) {
            return handleFailure(session, config, tokenType);
        }

        // 2. Map backup token to the required token if applicable
        TokenType resolvedToken = resolveBackupToken(session, config, tokenType);
        session.getValidatedTokens().add(resolvedToken);
        session.getAttemptCounts().remove(tokenType);
        // Bug 5 fix: also clear the required-slot count so stale failure counts from
        // the primary token don't persist after a successful backup submission
        if (resolvedToken != tokenType) {
            session.getAttemptCounts().remove(resolvedToken);
        }
        crossBrandEvaluator.recordValidated(session, tokenType);

        // 3. Evaluate progress toward targetLevel
        return evaluateProgress(session, config);
    }

    /**
     * Create a session from a call transfer with pre-validated external tokens.
     * The session is pre-populated with validated tokens and the caller's
     * current auth level from the source system.
     */
    public AuthenticateResponse transferSession(IvrSession session,
                                           BrandAuthConfig config,
                                           List<TokenType> validatedTokens,
                                           String sourceSystemId) {
        Instant now = Instant.now();

        for (TokenType tokenType : validatedTokens) {
            session.getValidatedTokens().add(tokenType);
            session.getCrossBrandTokens().put(tokenType,
                new CrossBrandTokenRecord(sourceSystemId, now));
        }

        sessionRepo.save(session);
        return evaluateProgress(session, config);
    }

    /**
     * Request to reach a higher auth level (mid-session upgrade).
     */
    public AuthenticateResponse escalate(String sessionId, AuthLevel newTarget) {
        IvrSession session = sessionRepo.getOrThrow(sessionId);
        AuthLevel current = session.getCurrentLevel();

        if (!newTarget.isHigherThan(current)) {
            throw new IllegalArgumentException("Target must exceed current level");
        }

        log.info("AUTH [{}] brand={} caller={} escalate {} -> {}",
            sessionId, session.getBrandId(), session.getCallerId(),
            current, newTarget);

        session.setTargetLevel(newTarget);
        sessionRepo.save(session);

        BrandAuthConfig config = rulesRegistry.get(session.getBrandId());
        return evaluateProgress(session, config);
    }

    /**
     * Evaluate whether the current validated tokens satisfy the target level.
     */
    public AuthenticateResponse evaluateProgress(IvrSession session, BrandAuthConfig config) {
        LevelRule rule = config.getLevelRules().get(session.getTargetLevel());
        if (rule == null) {
            throw new IllegalArgumentException("No rule defined for level: " + session.getTargetLevel());
        }

        Map<AuthLevel, Integer> pathIndexMap = session.getActivePathIndexByLevel();
        int activePathIdx = pathIndexMap.getOrDefault(session.getTargetLevel(), 0);

        if (activePathIdx >= rule.getPaths().size()) {
            // All paths exhausted — fail
            session.setStatus(SessionStatus.FAILED);
            sessionRepo.save(session);
            return baseResponse(session)
                .status(SessionStatus.FAILED)
                .prompt("Authentication failed. All retry attempts exhausted.")
                .build();
        }

        TokenPath activePath = rule.getPaths().get(activePathIdx);

        // Check if active path is fully satisfied (each required token must be directly validated)
        boolean pathComplete = true;
        for (TokenType required : activePath.getRequiredTokens()) {
            if (!session.getValidatedTokens().contains(required)) {
                pathComplete = false;
                break;
            }
        }

        if (pathComplete) {
            session.setCurrentLevel(session.getTargetLevel());
            session.setStatus(SessionStatus.AUTHENTICATED);
            sessionRepo.save(session);
            return baseResponse(session)
                .status(SessionStatus.AUTHENTICATED)
                .prompt("Authentication successful. You are now at " + session.getCurrentLevel() + " level.")
                .build();
        }

        // Find next missing token on this path (required tokens not yet validated)
        TokenType nextToken = null;
        for (TokenType required : activePath.getRequiredTokens()) {
            if (!session.getValidatedTokens().contains(required)) {
                nextToken = required;
                break;
            }
        }

        if (nextToken == null) {
            // Should not happen given pathComplete check above, but guard anyway
            session.setCurrentLevel(session.getTargetLevel());
            session.setStatus(SessionStatus.AUTHENTICATED);
            sessionRepo.save(session);
            return baseResponse(session)
                .status(SessionStatus.AUTHENTICATED)
                .build();
        }

        // Remember the original required token before preference filtering replaces it.
        // buildAcceptedTokens uses this to show ALL unblocked alternatives of the
        // original, not just the replacement's own (non-existent) backups.
        TokenType originalRequired = nextToken;

        // Apply customer preference filtering: if nextToken is blocked, try backups
        if (isBlocked(session, nextToken)) {
            nextToken = findAlternativeToken(session, activePath, nextToken);
            if (nextToken == null) {
                return advanceToNextPathOrFail(session, config, rule, activePathIdx);
            }
        }

        session.setStatus(SessionStatus.COLLECTING);
        sessionRepo.save(session);

        // Determine accepted tokens for this step (required token + any backups)
        List<TokenType> acceptedTokens = buildAcceptedTokens(session, activePath, nextToken, originalRequired);

        String prompt = promptResolver.resolvePrompt(nextToken, activePath, rule.getMaxRetriesPerToken());
        return baseResponse(session)
            .status(SessionStatus.COLLECTING)
            .nextRequiredToken(nextToken)
            .remainingAttempts(rule.getMaxRetriesPerToken())
            .acceptedTokens(acceptedTokens)
            .prompt(prompt)
            .build();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private AuthenticateResponse.AuthenticateResponseBuilder baseResponse(IvrSession session) {
        return AuthenticateResponse.builder()
            .sessionId(session.getSessionId())
            .phase(session.getPhase())
            .currentLevel(session.getCurrentLevel())
            .targetLevel(session.getTargetLevel())
            .matchedPartyId(session.getMatchedParty() != null
                ? session.getMatchedParty().getPartyId() : null);
    }

    /**
     * If the submitted token type matches a backup token for a required token
     * on the active path, map it to the required token so the path check passes.
     */
    private TokenType resolveBackupToken(IvrSession session, BrandAuthConfig config, TokenType submittedType) {
        LevelRule rule = config.getLevelRules().get(session.getTargetLevel());
        if (rule == null) return submittedType;

        int activePathIdx = session.getActivePathIndexByLevel().getOrDefault(session.getTargetLevel(), 0);
        if (activePathIdx >= rule.getPaths().size()) return submittedType;

        TokenPath activePath = rule.getPaths().get(activePathIdx);
        if (activePath.getBackupTokens() != null) {
            for (Map.Entry<TokenType, List<TokenType>> entry : activePath.getBackupTokens().entrySet()) {
                TokenType required = entry.getKey();
                List<TokenType> backups = entry.getValue();
                if (!session.getValidatedTokens().contains(required) && backups.contains(submittedType)) {
                    return required;
                }
            }
        }
        return submittedType;
    }

    /**
     * Given a submitted token type, return the required-token slot it belongs to on
     * the active path. If the submitted token is itself a required token, it is
     * returned unchanged. If it is a backup alternative for a required slot, the
     * required-slot token type is returned instead.
     * <p>
     * This is used by {@link #handleFailure} to ensure failure counts are always
     * accumulated at the required-token level so the retry limit cannot be bypassed
     * by cycling across backup token types (e.g., failing PIN, then SSN_LAST4, then
     * DATE_OF_BIRTH each counts against the same PIN slot).
     */
    private TokenType findRequiredTokenForSlot(IvrSession session,
                                               BrandAuthConfig config,
                                               TokenType submittedType) {
        LevelRule rule = config.getLevelRules().get(session.getTargetLevel());
        if (rule == null) return submittedType;

        int activePathIdx = session.getActivePathIndexByLevel()
            .getOrDefault(session.getTargetLevel(), 0);
        if (activePathIdx >= rule.getPaths().size()) return submittedType;

        TokenPath activePath = rule.getPaths().get(activePathIdx);
        if (activePath.getBackupTokens() != null) {
            for (Map.Entry<TokenType, List<TokenType>> entry : activePath.getBackupTokens().entrySet()) {
                if (entry.getValue().contains(submittedType)) {
                    return entry.getKey();   // return the required slot, not the backup
                }
            }
        }
        return submittedType;
    }

    private AuthenticateResponse handleFailure(IvrSession session,
                                           BrandAuthConfig config,
                                           TokenType tokenType) {
        LevelRule rule = config.getLevelRules().get(session.getTargetLevel());
        Map<TokenType, Integer> counts = session.getAttemptCounts();

        // Bug 1 fix: track attempts against the required-token slot, not the submitted
        // backup type. This prevents bypassing retry limits by cycling across backup types
        // (e.g., failing PIN once + SSN_LAST4 once + DATE_OF_BIRTH once = 3 total failures
        // that should trigger a path switch, not three independent 1-failure counters).
        TokenType requiredToken = findRequiredTokenForSlot(session, config, tokenType);
        int attempts = counts.containsKey(requiredToken)
            ? counts.get(requiredToken) + 1
            : 1;
        counts.put(requiredToken, attempts);
        int remaining = rule.getMaxRetriesPerToken() - attempts;

        if (remaining > 0) {
            int activePathIdx = session.getActivePathIndexByLevel()
                .getOrDefault(session.getTargetLevel(), 0);
            TokenPath activePath = rule.getPaths().get(activePathIdx);

            // Bug 2 fix: return the required-token slot (PIN) as nextRequiredToken,
            //   not the submitted backup type (SSN_LAST4).
            // Bug 3 fix: include acceptedTokens so the caller knows all valid alternatives.
            List<TokenType> acceptedTokens = buildAcceptedTokens(session, activePath, requiredToken, requiredToken);
            String prompt = promptResolver.resolvePrompt(requiredToken, activePath, remaining);
            sessionRepo.save(session);
            return baseResponse(session)
                .status(SessionStatus.COLLECTING)
                .nextRequiredToken(requiredToken)
                .remainingAttempts(remaining)
                .acceptedTokens(acceptedTokens)
                .prompt(prompt)
                .build();
        }

        // Retry limit exhausted — try advancing to the next fallback path
        Map<AuthLevel, Integer> pathIndexMap = session.getActivePathIndexByLevel();
        int currentPathIdx = pathIndexMap.getOrDefault(session.getTargetLevel(), 0);
        int nextPathIdx = currentPathIdx + 1;

        if (nextPathIdx < rule.getPaths().size()) {
            pathIndexMap.put(session.getTargetLevel(), nextPathIdx);
            session.getAttemptCounts().clear();
            sessionRepo.save(session);
            // Bug 4 fix: delegate to evaluateProgress() so the isBlocked /
            // findAlternativeToken checks are applied correctly on the new path,
            // instead of duplicating that logic here without the blocked-token guard.
            return evaluateProgress(session, config);
        }

        // All paths exhausted — lock the session
        session.setStatus(SessionStatus.LOCKED);
        session.setLockedUntil(Instant.now().plusSeconds(rule.getLockoutSeconds()));
        sessionRepo.save(session);
        log.warn("AUTH [{}] brand={} caller={} LOCKED for {} seconds",
            session.getSessionId(), session.getBrandId(), session.getCallerId(),
            rule.getLockoutSeconds());
        return baseResponse(session)
            .status(SessionStatus.LOCKED)
            .lockedUntil(session.getLockedUntil())
            .prompt("Authentication failed. All retry attempts exhausted.")
            .build();
    }

    private void pruneTokensNotInPath(IvrSession session, TokenPath newPath) {
        Set<TokenType> keep = new HashSet<>(newPath.getRequiredTokens());
        // Also keep any backup tokens that satisfy required tokens in the new path
        if (newPath.getBackupTokens() != null) {
            for (Map.Entry<TokenType, List<TokenType>> entry : newPath.getBackupTokens().entrySet()) {
                keep.addAll(entry.getValue());
            }
        }
        session.getValidatedTokens().retainAll(keep);
    }

    /**
     * Build the list of accepted token types for the next step.
     * Includes the required token plus any unblocked backup alternatives.
     *
     * @param originalRequiredToken the original required-token slot (used to look up
     *        backup alternatives even when nextToken was replaced by a preference-filtered alternative)
     */
    private List<TokenType> buildAcceptedTokens(IvrSession session, TokenPath activePath,
                                                 TokenType nextToken, TokenType originalRequiredToken) {
        List<TokenType> accepted = new ArrayList<>();
        accepted.add(nextToken);
        if (activePath.getBackupTokens() != null) {
            List<TokenType> backups = activePath.getBackupTokens().get(originalRequiredToken);
            if (backups != null) {
                for (TokenType backup : backups) {
                    if (!accepted.contains(backup) && !isBlocked(session, backup)) {
                        accepted.add(backup);
                    }
                }
            }
        }
        return accepted;
    }

    private boolean isBlocked(IvrSession session, TokenType tokenType) {
        if (session.getCustomerPreferences() == null) return false;
        Set<TokenType> blocked = session.getCustomerPreferences().getBlockedTokens();
        return blocked != null && blocked.contains(tokenType);
    }

    private TokenType findAlternativeToken(IvrSession session, TokenPath path, TokenType blockedToken) {
        if (path.getBackupTokens() == null) return null;
        List<TokenType> backups = path.getBackupTokens().get(blockedToken);
        if (backups == null) return null;
        for (TokenType backup : backups) {
            if (!isBlocked(session, backup)) {
                return backup;
            }
        }
        return null;
    }

    private AuthenticateResponse advanceToNextPathOrFail(IvrSession session, BrandAuthConfig config,
                                                      LevelRule rule, int currentPathIdx) {
        Map<AuthLevel, Integer> pathIndexMap = session.getActivePathIndexByLevel();
        int nextPathIdx = currentPathIdx + 1;

        if (nextPathIdx < rule.getPaths().size()) {
            pathIndexMap.put(session.getTargetLevel(), nextPathIdx);
            session.getAttemptCounts().clear();
            sessionRepo.save(session);
            return evaluateProgress(session, config);
        }

        session.setStatus(SessionStatus.FAILED);
        sessionRepo.save(session);
        return baseResponse(session)
            .status(SessionStatus.FAILED)
            .prompt("Authentication failed. No available authentication methods for your account.")
            .build();
    }

    private boolean validateExternally(IvrSession session,
                                        TokenType tokenType,
                                        String tokenValue) {
        TokenValidator validator = validatorRegistry.resolve(session.getBrandId(), tokenType);
        TokenValidationContext ctx = new TokenValidationContext(
            tokenType, tokenValue, session.getCallerId(),
            session.getCollectedTokens(), session.getBrandId()
        );
        return validator.validate(ctx).isValid();
    }

}