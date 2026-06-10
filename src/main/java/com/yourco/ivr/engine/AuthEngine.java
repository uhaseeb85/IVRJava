package com.yourco.ivr.engine;

import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.api.dto.ProcessingEvent;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.Party;
import com.yourco.ivr.domain.SessionPhase;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.LevelRule;
import com.yourco.ivr.domain.config.TokenPath;
import com.yourco.ivr.exception.SessionLockedException;
import com.yourco.ivr.exception.SessionNotFoundException;
import com.yourco.ivr.lookup.LookupRequest;
import com.yourco.ivr.lookup.LookupResult;
import com.yourco.ivr.lookup.LookupServiceRegistry;
import com.yourco.ivr.lookup.TokenLookupService;
import com.yourco.ivr.lookup.VerificationBinding;
import com.yourco.ivr.lookup.VerificationBindings;
import com.yourco.ivr.registry.BrandRulesRegistry;
import com.yourco.ivr.repository.SessionRepository;
import com.yourco.ivr.validator.TokenValidationContext;
import com.yourco.ivr.validator.TokenValidator;
import com.yourco.ivr.validator.TokenValidatorRegistry;
import com.yourco.ivr.validator.ValidationErrorCode;
import com.yourco.ivr.validator.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Core authentication state machine — the heart of the IVR auth engine.
 *
 * <p>This service owns all token-level business logic: validating submitted tokens,
 * managing retry budgets, switching between fallback paths, and determining when a session
 * has achieved its target auth level (or must be locked).
 *
 * <h2>Two-stage validation pipeline</h2>
 * <p>Each token submission goes through two gates in sequence:
 * <ol>
 *   <li><strong>Format gate</strong> — a lightweight {@link com.yourco.ivr.validator.TokenValidator}
 *       checks structure (length, format). Runs always, no network call.</li>
 *   <li><strong>Backend gate</strong> — if {@link com.yourco.ivr.lookup.VerificationBindings}
 *       binds the token type to a {@link com.yourco.ivr.lookup.TokenLookupService}, the engine
 *       calls the service to verify the value against a system of record. Skipped if no binding
 *       exists.</li>
 * </ol>
 *
 * <h2>Retry and path switching</h2>
 * <p>Failure counts are tracked at the <em>required-token-slot</em> level, not the submitted
 * type level. This prevents bypassing retry limits by cycling through backup alternatives
 * (e.g. failing PIN once + SSN_LAST4 once + DATE_OF_BIRTH once should count as three
 * failures against the PIN slot, not three independent single-failure counters).
 *
 * <p>When retries are exhausted on a path:
 * <ul>
 *   <li>For genuine validation failures (correct type, wrong value), the engine advances to
 *       the next fallback path defined in the {@link com.yourco.ivr.domain.config.LevelRule}.</li>
 *   <li>For wrong-type submissions, the session is locked immediately — no path switch —
 *       to prevent probing for valid token types.</li>
 *   <li>When all paths are exhausted, the session is locked for {@code lockoutSeconds}.</li>
 * </ul>
 *
 * <h2>Security invariants</h2>
 * <ul>
 *   <li>Raw token values are never logged — only {@link com.yourco.ivr.domain.TokenType} and
 *       outcome (PASS/FAIL).</li>
 *   <li>Backup token resolution maps an accepted alternative back to the required slot so
 *       session state always records the canonical required type, not the submitted type.</li>
 * </ul>
 *
 * @see com.yourco.ivr.service.AuthenticateService
 * @see com.yourco.ivr.engine.DisambiguationEngine
 */
@Service
public class AuthEngine {

    private static final Logger log = LoggerFactory.getLogger(AuthEngine.class);

    private final BrandRulesRegistry rulesRegistry;
    private final TokenValidatorRegistry validatorRegistry;
    private final LookupServiceRegistry lookupRegistry;
    private final VerificationBindings verificationBindings;
    private final SessionRepository sessionRepo;
    private final PromptResolver promptResolver;
    private final DisambiguationEngine disambiguationEngine;

    private final Map<TokenType, Function<Party, String>> partyFieldMap;

    public AuthEngine(BrandRulesRegistry rulesRegistry,
                      TokenValidatorRegistry validatorRegistry,
                      LookupServiceRegistry lookupRegistry,
                      VerificationBindings verificationBindings,
                      SessionRepository sessionRepo,
                      PromptResolver promptResolver,
                      DisambiguationEngine disambiguationEngine) {
        this.rulesRegistry = rulesRegistry;
        this.validatorRegistry = validatorRegistry;
        this.lookupRegistry = lookupRegistry;
        this.verificationBindings = verificationBindings;
        this.sessionRepo = sessionRepo;
        this.promptResolver = promptResolver;
        this.disambiguationEngine = disambiguationEngine;
        this.partyFieldMap = buildPartyFieldMap();
    }

    private static Map<TokenType, Function<Party, String>> buildPartyFieldMap() {
        Map<TokenType, Function<Party, String>> map = new LinkedHashMap<>();
        map.put(TokenType.ACCOUNT_NUMBER, Party::getAccountNumber);
        map.put(TokenType.DATE_OF_BIRTH, Party::getDateOfBirth);
        map.put(TokenType.SSN_LAST4, Party::getSsnLast4);
        map.put(TokenType.CARD_LAST4, Party::getCardLast4);
        return Collections.unmodifiableMap(map);
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

        resetExpiredRedirectOrThrow(session);
        verifyCallerOwnership(session, callerId);

        if (session.getStatus() == SessionStatus.AUTHENTICATED
                || session.getStatus() == SessionStatus.FAILED) {
            return AuthenticateResponse.fromSession(session);
        }

        BrandAuthConfig config = rulesRegistry.get(session.getBrandId());

        session.getCollectedTokens().put(tokenType, tokenValue);

        // Route to disambiguation if session is still resolving parties
        if (session.getPhase() == SessionPhase.DISAMBIGUATION) {
            return handleDisambiguationPhase(session, config, tokenType, tokenValue);
        }

        // ── Build per-request processing log ────────────────────────────────
        List<ProcessingEvent> procLog = new ArrayList<>();

        ActivePath active = resolveActivePath(session, config);
        TokenType nextRequired = active.path != null
            ? findNextRequired(session.getValidatedTokens(), active.path) : null;
        List<TokenType> acceptedForSlot = nextRequired != null
            ? buildAcceptedTokens(session, active.path, nextRequired, nextRequired) : null;

        logCollectionContext(procLog, session, active, nextRequired, acceptedForSlot);

        // Guard: reject token types not accepted at the current step.
        // Wrong-type submissions decrement the required slot's retry budget but do NOT
        // trigger a path switch — path switching is reserved for genuine validation failures
        // (correct type, wrong value). Exhausting retries on wrong-type locks the session.
        if (nextRequired != null && !acceptedForSlot.contains(tokenType)) {
            addEntry(procLog, "WARN",
                "WRONG_TYPE: submitted " + tokenType + " but expected one of " + acceptedForSlot);
            AuthenticateResponse wrongTypeResp = handleWrongTypeFailure(session, config, nextRequired, procLog);
            wrongTypeResp.setProcessingLog(procLog);
            return wrongTypeResp;
        }

        // 1. Validate externally
        ValidationResult validationResult = validateExternally(session, tokenType, tokenValue);
        boolean valid = validationResult.isValid();

        addEntry(procLog, valid ? "PASS" : "FAIL",
            "External validation: " + tokenType + " → " + describeValidationOutcome(validationResult));

        log.info("AUTH [{}] brand={} caller={} token={} result={}",
            sessionId, session.getBrandId(), session.getCallerId(), tokenType,
            valid ? "PASS" : "FAIL");

        if (!valid) {
            AuthenticateResponse failResp = handleValidationFailure(session, config, tokenType, procLog);
            failResp.setProcessingLog(procLog);
            return failResp;
        }

        // 2. Map backup token to the required token if applicable
        TokenType resolvedToken = resolveBackupToken(session, config, tokenType);
        if (resolvedToken != tokenType) {
            addEntry(procLog, "INFO",
                "Backup resolution: " + tokenType + " satisfies required slot " + resolvedToken);
        } else {
            addEntry(procLog, "INFO",
                tokenType + " is a direct required token (no backup mapping)");
        }

        session.getValidatedTokens().add(resolvedToken);
        session.getAttemptCounts().remove(tokenType);
        // Bug 5 fix: also clear the required-slot count so stale failure counts from
        // the primary token don't persist after a successful backup submission
        if (resolvedToken != tokenType) {
            session.getAttemptCounts().remove(resolvedToken);
        }

        addEntry(procLog, "INFO",
            "Validated tokens now: " + session.getValidatedTokens());

        // 3. Evaluate progress toward targetLevel
        AuthenticateResponse evalResp = evaluateProgress(session, config);
        if (evalResp.getStatus() == SessionStatus.AUTHENTICATED) {
            addEntry(procLog, "PASS",
                "Path complete → " + session.getTargetLevel() + " achieved");
        } else {
            logNextRequired(procLog, evalResp);
        }
        evalResp.setProcessingLog(procLog);
        return evalResp;
    }

    /** Auto-resets an expired redirect-to-agent lock, or throws if it is still active. */
    private void resetExpiredRedirectOrThrow(IvrSession session) {
        if (session.getStatus() != SessionStatus.REDIRECT_TO_AGENT) {
            return;
        }
        if (session.getLockedUntil() == null || !Instant.now().isAfter(session.getLockedUntil())) {
            throw new SessionLockedException(session.getSessionId(), session.getLockedUntil());
        }
        session.setStatus(SessionStatus.COLLECTING);
        session.getAttemptCounts().clear();
        session.setLockedUntil(null);
        sessionRepo.save(session);
    }

    /** Optional session ownership validation: a mismatched callerId may not act on the session. */
    private static void verifyCallerOwnership(IvrSession session, String callerId) {
        if (callerId != null && !callerId.equals(session.getCallerId())) {
            log.warn("CallerId mismatch for session {}: expected {}, got {}",
                session.getSessionId(), session.getCallerId(), callerId);
            throw new SessionNotFoundException(session.getSessionId());
        }
    }

    /** Forwards the token to the disambiguation round; hands off to auth once a party resolves. */
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

    /** Logs where the session stands before processing the submitted token. */
    private static void logCollectionContext(List<ProcessingEvent> procLog,
                                             IvrSession session,
                                             ActivePath active,
                                             TokenType nextRequired,
                                             List<TokenType> acceptedForSlot) {
        addEntry(procLog, "INFO",
            "Brand: " + session.getBrandId()
            + " | Target: " + session.getTargetLevel()
            + " | Phase: AUTHENTICATING");

        if (active.path != null) {
            addEntry(procLog, "INFO",
                "Active path: path" + active.index + " → " + active.path.getRequiredTokens());
        }

        Set<TokenType> validatedSoFar = session.getValidatedTokens();
        addEntry(procLog, "INFO",
            "Validated tokens: " + (validatedSoFar.isEmpty() ? "[none]" : validatedSoFar));

        if (nextRequired != null) {
            addEntry(procLog, "INFO",
                "Collecting: " + nextRequired + " | Accepted alternatives: " + acceptedForSlot);
        }
    }

    /** PASS, the specific error code when present, or FAIL. Never includes the token value. */
    private static String describeValidationOutcome(ValidationResult result) {
        if (result.isValid()) {
            return "PASS";
        }
        return result.getErrorCode() != null ? result.getErrorCode().name() : "FAIL";
    }

    /** Logs the next required slot and its accepted alternatives, when present. */
    private static void logNextRequired(List<ProcessingEvent> procLog, AuthenticateResponse resp) {
        if (resp.getNextRequiredToken() == null) {
            return;
        }
        addEntry(procLog, "INFO", "Next required: " + resp.getNextRequiredToken());
        if (resp.getAcceptedTokens() != null && !resp.getAcceptedTokens().isEmpty()) {
            addEntry(procLog, "INFO", "Accepted for next slot: " + resp.getAcceptedTokens());
        }
    }

    /**
     * Create a session from a call transfer with pre-validated external tokens.
     * The session is pre-populated with validated tokens and the caller's
     * current auth level from the source system.
     */
    public AuthenticateResponse transferSession(IvrSession session,
                                           BrandAuthConfig config,
                                           List<TokenType> validatedTokens) {
        for (TokenType tokenType : validatedTokens) {
            session.getValidatedTokens().add(tokenType);
        }

        sessionRepo.save(session);
        return onPartyResolved(session, config);
    }

    /**
     * Request to reach a higher auth level (mid-session upgrade).
     */
    public AuthenticateResponse escalate(String sessionId, AuthLevel newTarget) {
        IvrSession session = sessionRepo.getOrThrow(sessionId);
        AuthLevel current = session.getCurrentLevel();

        BrandAuthConfig config = rulesRegistry.get(session.getBrandId());
        if (config.isIdentificationOnly()) {
            throw new IllegalArgumentException("Escalation is not supported for identification-only brands");
        }

        if (!newTarget.isHigherThan(current)) {
            throw new IllegalArgumentException("Target must exceed current level");
        }

        log.info("AUTH [{}] brand={} caller={} escalate {} -> {}",
            sessionId, session.getBrandId(), session.getCallerId(),
            current, newTarget);

        session.setTargetLevel(newTarget);
        sessionRepo.save(session);

        return evaluateProgress(session, config);
    }

    /**
     * Called once a single party has been resolved (directly or via disambiguation).
     * In identification-only mode the session is complete — there is nothing to authenticate;
     * otherwise the flow proceeds to collect tokens toward {@code targetLevel}.
     *
     * <p>When identification-only with {@code levelRules[NONE]}, disambiguation tokens
     * that match required identification tokens are transferred to {@code validatedTokens}
     * to avoid double-prompting the caller for the same information.
     */
    public AuthenticateResponse onPartyResolved(IvrSession session, BrandAuthConfig config) {
        if (config.isIdentificationOnly()) {
            if (config.getLevelRules() != null && config.getLevelRules().containsKey(AuthLevel.NONE)) {
                LevelRule noneRule = config.getLevelRules().get(AuthLevel.NONE);
                for (TokenType collected : session.getCollectedTokens().keySet()) {
                    for (TokenPath path : noneRule.getPaths()) {
                        if (path.getRequiredTokens().contains(collected)) {
                            session.getValidatedTokens().add(collected);
                            break;
                        }
                    }
                }
                return evaluateProgress(session, config);
            }
            return finalizeIdentification(session);
        }
        return evaluateProgress(session, config);
    }

    /**
     * Finalizes an identification-only session: the caller is identified, access level stays
     * {@link AuthLevel#NONE}, and the session reports {@link SessionStatus#AUTHENTICATED}.
     */
    private AuthenticateResponse finalizeIdentification(IvrSession session) {
        session.setCurrentLevel(AuthLevel.NONE);
        session.setTargetLevel(AuthLevel.NONE);
        session.setStatus(SessionStatus.AUTHENTICATED);
        sessionRepo.save(session);
        log.info("AUTH [{}] brand={} caller={} IDENTIFICATION_ONLY resolved party={}",
            session.getSessionId(), session.getBrandId(), session.getCallerId(),
            session.getMatchedParty() != null ? session.getMatchedParty().getPartyId() : "null");
        return baseResponse(session)
            .status(SessionStatus.AUTHENTICATED)
            .prompt("Caller identified.")
            .build();
    }

    /**
     * Evaluate whether the current validated tokens satisfy the target level.
     */
    public AuthenticateResponse evaluateProgress(IvrSession session, BrandAuthConfig config) {
        ActivePath active = resolveActivePath(session, config);
        if (active.rule == null) {
            throw new IllegalArgumentException("No rule defined for level: " + session.getTargetLevel());
        }

        if (active.path == null) {
            // Active index points past the last path — all paths exhausted, fail.
            session.setStatus(SessionStatus.FAILED);
            sessionRepo.save(session);
            return baseResponse(session)
                .status(SessionStatus.FAILED)
                .prompt("Authentication failed. All retry attempts exhausted.")
                .build();
        }

        LevelRule rule = active.rule;
        TokenPath activePath = active.path;

        // Next missing required token on this path; null means the path is fully satisfied
        TokenType nextToken = findNextRequired(session.getValidatedTokens(), activePath);
        if (nextToken == null) {
            return completeAuthentication(session, config);
        }

        // Remember the original required token before preference filtering replaces it.
        // buildAcceptedTokens uses this to show ALL unblocked alternatives of the
        // original, not just the replacement's own (non-existent) backups.
        TokenType originalRequired = nextToken;

        // Apply customer preference filtering: if nextToken is blocked, try backups
        if (isBlocked(session, nextToken)) {
            nextToken = findAlternativeToken(session, activePath, nextToken);
            if (nextToken == null) {
                return advanceToNextPathOrFail(session, config, rule, active.index);
            }
        }

        session.setStatus(SessionStatus.COLLECTING);
        sessionRepo.save(session);

        // Determine accepted tokens for this step (required token + any backups)
        List<TokenType> acceptedTokens = buildAcceptedTokens(session, activePath, nextToken, originalRequired);

        String prompt = promptResolver.resolvePrompt(nextToken, activePath, rule.getMaxRetriesFor(nextToken));
        return baseResponse(session)
            .status(SessionStatus.COLLECTING)
            .nextRequiredToken(nextToken)
            .remainingAttempts(rule.getMaxRetriesFor(nextToken))
            .acceptedTokens(acceptedTokens)
            .prompt(prompt)
            .build();
    }

    /** Active path fully satisfied — mark the session authenticated at its target level. */
    private AuthenticateResponse completeAuthentication(IvrSession session, BrandAuthConfig config) {
        if (config.isIdentificationOnly() && session.getTargetLevel() == AuthLevel.NONE) {
            return finalizeIdentification(session);
        }
        session.setCurrentLevel(session.getTargetLevel());
        session.setStatus(SessionStatus.AUTHENTICATED);
        sessionRepo.save(session);
        return baseResponse(session)
            .status(SessionStatus.AUTHENTICATED)
            .prompt("Authentication successful. You are now at " + session.getCurrentLevel() + " level.")
            .build();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /** Constructs a response builder pre-populated with fields common to all responses. */
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
     * Snapshot of the path the engine is currently collecting against for a session's
     * target level: the level's {@link LevelRule}, the active path index, and the
     * {@link TokenPath} at that index.
     *
     * <p>Either reference may be {@code null}: {@code rule} is {@code null} when the brand
     * defines no rule for the target level, and {@code path} is {@code null} when the active
     * index points past the last path (i.e. all fallback paths are exhausted).
     */
    private static final class ActivePath {
        final LevelRule rule;
        final int index;
        final TokenPath path;

        ActivePath(LevelRule rule, int index, TokenPath path) {
            this.rule = rule;
            this.index = index;
            this.path = path;
        }
    }

    /** Resolves the {@link ActivePath} for the session's current target level. */
    private ActivePath resolveActivePath(IvrSession session, BrandAuthConfig config) {
        LevelRule rule = config.getLevelRules() != null
            ? config.getLevelRules().get(session.getTargetLevel()) : null;
        int index = session.getActivePathIndexByLevel().getOrDefault(session.getTargetLevel(), 0);
        TokenPath path = (rule != null && index < rule.getPaths().size())
            ? rule.getPaths().get(index) : null;
        return new ActivePath(rule, index, path);
    }

    /**
     * If the submitted token type matches a backup token for a required token
     * on the active path, map it to the required token so the path check passes.
     */
    private TokenType resolveBackupToken(IvrSession session, BrandAuthConfig config, TokenType submittedType) {
        TokenPath activePath = resolveActivePath(session, config).path;
        if (activePath == null) return submittedType;

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
     * This is used by the failure handlers to ensure failure counts are always
     * accumulated at the required-token level so the retry limit cannot be bypassed
     * by cycling across backup token types (e.g., failing PIN, then SSN_LAST4, then
     * DATE_OF_BIRTH each counts against the same PIN slot).
     */
    private TokenType findRequiredTokenForSlot(IvrSession session,
                                                BrandAuthConfig config,
                                                TokenType submittedType) {
        TokenPath activePath = resolveActivePath(session, config).path;
        if (activePath == null) return submittedType;

        if (activePath.getBackupTokens() != null) {
            for (Map.Entry<TokenType, List<TokenType>> entry : activePath.getBackupTokens().entrySet()) {
                if (entry.getValue().contains(submittedType)) {
                    return entry.getKey();   // return the required slot, not the backup
                }
            }
        }
        return submittedType;
    }

    /**
     * Handle a genuine validation failure (correct type, wrong value). When the retry
     * budget for the required slot is exhausted, the engine may switch to the next
     * fallback path before locking the session.
     */
    private AuthenticateResponse handleValidationFailure(IvrSession session,
                                                         BrandAuthConfig config,
                                                         TokenType tokenType,
                                                         List<ProcessingEvent> procLog) {
        LevelRule rule = levelRule(config, session);
        TokenType requiredToken = findRequiredTokenForSlot(session, config, tokenType);
        int remaining = recordFailedAttempt(session, rule, requiredToken, procLog);
        if (remaining > 0) {
            return repromptForSlot(session, rule, requiredToken, remaining, procLog);
        }

        addEntry(procLog, "WARN", "Retry limit exhausted for " + requiredToken + " slot");
        AuthenticateResponse switched = switchToFallbackPath(session, config, rule, procLog);
        if (switched != null) {
            return switched;
        }
        return redirectToAgent(session, rule, procLog);
    }

    /**
     * Handle a wrong-token-type submission. Retry exhaustion locks the session
     * immediately — path switching is NOT offered, because presenting a fresh retry
     * budget for an alternative credential method rewards the wrong behaviour.
     */
    private AuthenticateResponse handleWrongTypeFailure(IvrSession session,
                                                        BrandAuthConfig config,
                                                        TokenType tokenType,
                                                        List<ProcessingEvent> procLog) {
        LevelRule rule = levelRule(config, session);
        TokenType requiredToken = findRequiredTokenForSlot(session, config, tokenType);
        int remaining = recordFailedAttempt(session, rule, requiredToken, procLog);
        if (remaining > 0) {
            return repromptForSlot(session, rule, requiredToken, remaining, procLog);
        }

        addEntry(procLog, "WARN", "Retry limit exhausted for " + requiredToken + " slot");
        addEntry(procLog, "FAIL",
            "Wrong token type exhausted retries — redirecting to agent (no path switch)");
        return redirectToAgent(session, rule, procLog);
    }

    /** The level rule for the session's current target level. */
    private static LevelRule levelRule(BrandAuthConfig config, IvrSession session) {
        return config.getLevelRules() != null
            ? config.getLevelRules().get(session.getTargetLevel()) : null;
    }

    /**
     * Record a failed attempt against the required-token slot and return the remaining
     * retry budget. Attempts are tracked per required slot (not per submitted type) so
     * the limit cannot be bypassed by cycling across backup alternatives.
     */
    private static int recordFailedAttempt(IvrSession session,
                                           LevelRule rule,
                                           TokenType requiredToken,
                                           List<ProcessingEvent> procLog) {
        Map<TokenType, Integer> counts = session.getAttemptCounts();
        int attempts = counts.containsKey(requiredToken) ? counts.get(requiredToken) + 1 : 1;
        counts.put(requiredToken, attempts);
        int maxRetries = rule.getMaxRetriesFor(requiredToken);

        addEntry(procLog, "WARN",
            "Attempt " + attempts + " of " + maxRetries
            + " for " + requiredToken + " slot");
        return maxRetries - attempts;
    }

    /**
     * Re-prompt for the same required slot with the remaining retry budget.
     * The response carries the required-token slot (not the submitted backup type) as
     * nextRequiredToken, plus acceptedTokens so the caller knows all valid alternatives.
     */
    private AuthenticateResponse repromptForSlot(IvrSession session,
                                                 LevelRule rule,
                                                 TokenType requiredToken,
                                                 int remaining,
                                                 List<ProcessingEvent> procLog) {
        addEntry(procLog, "WARN",
            remaining + " attempt" + (remaining == 1 ? "" : "s")
            + " remaining — still collecting " + requiredToken);

        int activePathIdx = session.getActivePathIndexByLevel()
            .getOrDefault(session.getTargetLevel(), 0);
        TokenPath activePath = rule.getPaths().get(activePathIdx);

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

    /**
     * Advance to the next fallback path, if one exists, and re-evaluate progress on it.
     * Returns {@code null} when all paths are exhausted — the caller falls through to lockout.
     */
    private AuthenticateResponse switchToFallbackPath(IvrSession session,
                                                      BrandAuthConfig config,
                                                      LevelRule rule,
                                                      List<ProcessingEvent> procLog) {
        Map<AuthLevel, Integer> pathIndexMap = session.getActivePathIndexByLevel();
        int nextPathIdx = pathIndexMap.getOrDefault(session.getTargetLevel(), 0) + 1;
        if (nextPathIdx >= rule.getPaths().size()) {
            return null;
        }
        TokenPath newPath = rule.getPaths().get(nextPathIdx);

        // List tokens in the new path already validated so the log shows what carries over
        List<TokenType> alreadyValid = new ArrayList<>();
        for (TokenType t : newPath.getRequiredTokens()) {
            if (session.getValidatedTokens().contains(t)) {
                alreadyValid.add(t);
            }
        }
        addEntry(procLog, "WARN",
            "Switching to fallback: path" + nextPathIdx + " → " + newPath.getRequiredTokens());
        if (!alreadyValid.isEmpty()) {
            addEntry(procLog, "INFO",
                "Pre-validated tokens retained in new path: " + alreadyValid);
        }

        pathIndexMap.put(session.getTargetLevel(), nextPathIdx);
        session.getAttemptCounts().clear();
        sessionRepo.save(session);
        // Delegate to evaluateProgress() so the isBlocked / findAlternativeToken checks
        // are applied correctly on the new path.
        AuthenticateResponse switchResp = evaluateProgress(session, config);
        logNextRequired(procLog, switchResp);
        return switchResp;
    }

    /** All retries exhausted with no path remaining — lock the session and redirect to agent. */
    private AuthenticateResponse redirectToAgent(IvrSession session,
                                                 LevelRule rule,
                                                 List<ProcessingEvent> procLog) {
        addEntry(procLog, "FAIL", "Redirect to agent after " + rule.getLockoutSeconds() + " seconds");

        session.setStatus(SessionStatus.REDIRECT_TO_AGENT);
        session.setLockedUntil(Instant.now().plusSeconds(rule.getLockoutSeconds()));
        sessionRepo.save(session);
        log.warn("AUTH [{}] brand={} caller={} REDIRECT_TO_AGENT for {} seconds",
            session.getSessionId(), session.getBrandId(), session.getCallerId(),
            rule.getLockoutSeconds());
        return baseResponse(session)
            .status(SessionStatus.REDIRECT_TO_AGENT)
            .lockedUntil(session.getLockedUntil())
            .prompt("Authentication failed. All retry attempts exhausted. Redirecting to agent.")
            .build();
    }

    /** Appends a structured entry to the per-request processing log. */
    private static void addEntry(List<ProcessingEvent> procLog, String level, String message) {
        procLog.add(ProcessingEvent.builder().level(level).message(message).build());
    }

    private static TokenType findNextRequired(Set<TokenType> validated, TokenPath path) {
        for (TokenType req : path.getRequiredTokens()) {
            if (!validated.contains(req)) {
                return req;
            }
        }
        return null;
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

    /** Returns {@code true} if the customer's preferences block the given token type. */
    private boolean isBlocked(IvrSession session, TokenType tokenType) {
        if (session.getCustomerPreferences() == null) return false;
        Set<TokenType> blocked = session.getCustomerPreferences().getBlockedTokens();
        return blocked != null && blocked.contains(tokenType);
    }

    /**
     * Finds the first unblocked backup alternative for {@code blockedToken} on the given path.
     * Returns {@code null} if no unblocked alternative exists (caller should advance path).
     */
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

    /**
     * Advances the active path index to the next fallback path, or sets the session to
     * {@link com.yourco.ivr.domain.SessionStatus#FAILED} if no further paths exist.
     */
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

    /**
     * Runs the two-stage validation pipeline for the submitted token.
     *
     * <p>Stage 1 (format gate): cheap, in-process format check via {@link TokenValidatorRegistry}.
     * Returns early with a failure if the format is invalid, sparing the backend call.
     * Stage 2 (backend gate): only runs if a {@link VerificationBinding} is wired in code for
     * this token type — see {@link VerificationBindings}.
     */
    private ValidationResult validateExternally(IvrSession session,
                                               TokenType tokenType,
                                               String tokenValue) {
        // ── Stage 1: format gate (cheap, no network) ─────────────────────────
        TokenValidator validator = validatorRegistry.resolve(session.getBrandId(), tokenType);
        TokenValidationContext ctx = new TokenValidationContext(
            tokenType, tokenValue, session.getCallerId(),
            session.getCollectedTokens(), session.getBrandId()
        );
        ValidationResult formatResult = validator.validate(ctx);
        if (!formatResult.isValid()) {
            return formatResult;
        }

        // ── Party-field verification (identification-only brands) ──────────
        BrandAuthConfig config = rulesRegistry.get(session.getBrandId());
        if (config.isIdentificationOnly()) {
            ValidationResult partyResult = verifyAgainstParty(session, tokenType, tokenValue);
            if (!partyResult.isValid()) {
                return partyResult;
            }
        }

        // ── Stage 2: backend verification (only if this token is bound to a service) ──
        VerificationBinding binding = verificationBindings.bindingFor(session.getBrandId(), tokenType);
        if (binding == null) {
            return ValidationResult.ok();  // format check only — no backend gate for this token
        }
        return verifyAgainstBackend(session, tokenType, tokenValue, binding);
    }

    /**
     * Verifies a token value against the matched party's corresponding field.
     * Used by identification-only brands to confirm caller identity.
     *
     * <p>Token types without a party-field mapping (PIN, OTP, VOICE_PRINT) are skipped
     * and return {@link ValidationResult#ok()}. Tokens whose party field is null
     * (no data to match against) are also skipped.
     */
    private ValidationResult verifyAgainstParty(IvrSession session, TokenType tokenType,
                                                String tokenValue) {
        Party party = session.getMatchedParty();
        if (party == null) return ValidationResult.ok();

        Function<Party, String> extractor = partyFieldMap.get(tokenType);
        if (extractor == null) return ValidationResult.ok();

        String expectedValue = extractor.apply(party);
        if (expectedValue == null) return ValidationResult.ok();

        if (expectedValue.equals(tokenValue)) {
            return ValidationResult.ok();
        }
        log.info("PARTY_VERIFY [{}] token={} value mismatch for party={}",
            session.getSessionId(), tokenType, party.getPartyId());
        return ValidationResult.fail(ValidationErrorCode.VERIFICATION_FAILED);
    }

    /**
     * Calls the configured backend lookup service to verify the token value.
     * On any {@link RuntimeException} (timeout, unavailable service, unknown ID), behaviour
     * is governed by {@link VerificationBinding#isFailClosed()}:
     * {@code true} (default) → treat as a validation failure;
     * {@code false} → pass the token through on the format check alone.
     */
    private ValidationResult verifyAgainstBackend(IvrSession session,
                                                  TokenType tokenType,
                                                  String tokenValue,
                                                  VerificationBinding binding) {
        try {
            TokenLookupService service = lookupRegistry.get(binding.getServiceId());
            LookupRequest request = new LookupRequest(
                tokenType, tokenValue, session.getCallerId(),
                session.getBrandId(), session.getCollectedTokens(), binding.getParams()
            );
            LookupResult result = service.verify(request);
            // Token value never logged — only type, service and outcome.
            log.info("LOOKUP [{}] brand={} token={} service={} result={}",
                session.getSessionId(), session.getBrandId(), tokenType,
                binding.getServiceId(), result.isVerified() ? "VERIFIED" : "REJECTED");
            return result.isVerified()
                ? ValidationResult.ok()
                : ValidationResult.fail(result.getCode() != null
                    ? result.getCode() : ValidationErrorCode.VERIFICATION_FAILED);
        } catch (RuntimeException ex) {
            // Backend unavailable (timeout, error, or unknown service id).
            log.warn("LOOKUP [{}] brand={} token={} service={} UNAVAILABLE failClosed={}: {}",
                session.getSessionId(), session.getBrandId(), tokenType,
                binding.getServiceId(), binding.isFailClosed(), ex.getMessage());
            return binding.isFailClosed()
                ? ValidationResult.fail(ValidationErrorCode.VERIFICATION_UNAVAILABLE)
                : ValidationResult.ok();
        }
    }

}