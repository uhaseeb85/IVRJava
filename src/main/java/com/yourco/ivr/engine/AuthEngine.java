package com.yourco.ivr.engine;

import com.yourco.ivr.api.dto.SessionResponse;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.LevelRule;
import com.yourco.ivr.domain.config.TokenPath;
import com.yourco.ivr.registry.BrandRulesRegistry;
import com.yourco.ivr.repository.SessionRepository;
import com.yourco.ivr.validator.TokenValidationContext;
import com.yourco.ivr.validator.TokenValidator;
import com.yourco.ivr.validator.TokenValidatorRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public class AuthEngine {

    private final BrandRulesRegistry rulesRegistry;
    private final TokenValidatorRegistry validatorRegistry;
    private final SessionRepository sessionRepo;
    private final CrossBrandTokenEvaluator crossBrandEvaluator;
    private final PromptResolver promptResolver;

    public AuthEngine(BrandRulesRegistry rulesRegistry,
                      TokenValidatorRegistry validatorRegistry,
                      SessionRepository sessionRepo,
                      CrossBrandTokenEvaluator crossBrandEvaluator,
                      PromptResolver promptResolver) {
        this.rulesRegistry = rulesRegistry;
        this.validatorRegistry = validatorRegistry;
        this.sessionRepo = sessionRepo;
        this.crossBrandEvaluator = crossBrandEvaluator;
        this.promptResolver = promptResolver;
    }

    /**
     * Called when the IVR platform submits a token value.
     */
    public SessionResponse submitToken(String sessionId,
                                       TokenType tokenType,
                                       String tokenValue) {
        IvrSession session = sessionRepo.getOrThrow(sessionId);
        BrandAuthConfig config = rulesRegistry.get(session.getBrandId());

        // 1. Lockout guard
        if (isLocked(session)) {
            return buildLockedResponse(session);
        }

        // Store the collected token value
        session.getCollectedTokens().put(tokenType, tokenValue);

        // 2. Check cross-brand token shortcut before calling external API
        boolean valid = crossBrandEvaluator.isAccepted(session, config, tokenType, tokenValue)
            || validateExternally(session, tokenType, tokenValue);

        if (!valid) {
            return handleFailure(session, config, tokenType);
        }

        // 3. Mark validated and record cross-brand provenance
        session.getValidatedTokens().add(tokenType);
        session.getAttemptCounts().remove(tokenType);
        crossBrandEvaluator.recordValidated(session, tokenType);

        // 4. Evaluate progress toward targetLevel
        return evaluateProgress(session, config);
    }

    /**
     * Request to reach a higher auth level (mid-session upgrade).
     */
    public SessionResponse escalate(String sessionId, AuthLevel newTarget) {
        IvrSession session = sessionRepo.getOrThrow(sessionId);
        AuthLevel current = session.getCurrentLevel();

        if (!newTarget.isHigherThan(current)) {
            throw new IllegalArgumentException("Target must exceed current level");
        }

        session.setTargetLevel(newTarget);
        sessionRepo.save(session);

        BrandAuthConfig config = rulesRegistry.get(session.getBrandId());
        return evaluateProgress(session, config);
    }

    /**
     * Evaluate whether the current validated tokens satisfy the target level.
     */
    public SessionResponse evaluateProgress(IvrSession session, BrandAuthConfig config) {
        LevelRule rule = config.getLevelRules().get(session.getTargetLevel());
        if (rule == null) {
            throw new IllegalArgumentException("No rule defined for level: " + session.getTargetLevel());
        }

        Map<AuthLevel, Integer> pathIndexMap = session.getActivePathIndexByLevel();
        int activePathIdx = pathIndexMap.getOrDefault(session.getTargetLevel(), 0);

        if (activePathIdx >= rule.getPaths().size()) {
            // All paths exhausted — lock
            session.setStatus(SessionStatus.LOCKED);
            session.setLockedUntil(Instant.now().plusSeconds(rule.getLockoutSeconds()));
            sessionRepo.save(session);
            return buildLockedResponse(session);
        }

        TokenPath activePath = rule.getPaths().get(activePathIdx);

        // Check if active path is fully satisfied
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
            return SessionResponse.builder()
                .sessionId(session.getSessionId())
                .status(SessionStatus.AUTHENTICATED)
                .currentLevel(session.getCurrentLevel())
                .targetLevel(session.getTargetLevel())
                .prompt("Authentication successful. You are now at " + session.getCurrentLevel() + " level.")
                .build();
        }

        // Find next missing token on this path
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
            return SessionResponse.builder()
                .sessionId(session.getSessionId())
                .status(SessionStatus.AUTHENTICATED)
                .currentLevel(session.getCurrentLevel())
                .targetLevel(session.getTargetLevel())
                .build();
        }

        session.setStatus(SessionStatus.COLLECTING);
        sessionRepo.save(session);

        String prompt = promptResolver.resolvePrompt(nextToken, activePath, rule.getMaxRetriesPerToken());
        return SessionResponse.builder()
            .sessionId(session.getSessionId())
            .status(SessionStatus.COLLECTING)
            .currentLevel(session.getCurrentLevel())
            .targetLevel(session.getTargetLevel())
            .nextRequiredToken(nextToken)
            .remainingAttempts(rule.getMaxRetriesPerToken())
            .prompt(prompt)
            .build();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private SessionResponse handleFailure(IvrSession session,
                                           BrandAuthConfig config,
                                           TokenType tokenType) {
        LevelRule rule = config.getLevelRules().get(session.getTargetLevel());
        Map<TokenType, Integer> counts = session.getAttemptCounts();
        int attempts = counts.containsKey(tokenType)
            ? counts.get(tokenType) + 1
            : 1;
        counts.put(tokenType, attempts);
        int remaining = rule.getMaxRetriesPerToken() - attempts;

        if (remaining > 0) {
            sessionRepo.save(session);
            String prompt = promptResolver.resolvePrompt(tokenType, null, remaining);
            return SessionResponse.builder()
                .sessionId(session.getSessionId())
                .status(SessionStatus.COLLECTING)
                .currentLevel(session.getCurrentLevel())
                .targetLevel(session.getTargetLevel())
                .nextRequiredToken(tokenType)
                .remainingAttempts(remaining)
                .prompt(prompt)
                .build();
        }

        // Try advancing to next fallback path
        Map<AuthLevel, Integer> pathIndexMap = session.getActivePathIndexByLevel();
        int currentPathIdx = pathIndexMap.getOrDefault(session.getTargetLevel(), 0);
        int nextPathIdx = currentPathIdx + 1;

        if (nextPathIdx < rule.getPaths().size()) {
            pathIndexMap.put(session.getTargetLevel(), nextPathIdx);
            session.getAttemptCounts().clear();
            TokenPath nextPath = rule.getPaths().get(nextPathIdx);
            pruneTokensNotInPath(session, nextPath);
            sessionRepo.save(session);

            TokenType nextToken = null;
            for (TokenType required : nextPath.getRequiredTokens()) {
                if (!session.getValidatedTokens().contains(required)) {
                    nextToken = required;
                    break;
                }
            }

            if (nextToken == null) {
                // All tokens in fallback path already validated — re-evaluate
                return evaluateProgress(session, config);
            }

            String prompt = promptResolver.resolvePrompt(nextToken, nextPath, rule.getMaxRetriesPerToken());
            return SessionResponse.builder()
                .sessionId(session.getSessionId())
                .status(SessionStatus.COLLECTING)
                .currentLevel(session.getCurrentLevel())
                .targetLevel(session.getTargetLevel())
                .nextRequiredToken(nextToken)
                .remainingAttempts(rule.getMaxRetriesPerToken())
                .prompt("Fallback: " + prompt)
                .build();
        }

        // All paths exhausted — lock
        session.setStatus(SessionStatus.LOCKED);
        session.setLockedUntil(Instant.now().plusSeconds(rule.getLockoutSeconds()));
        sessionRepo.save(session);
        return buildLockedResponse(session);
    }

    private void pruneTokensNotInPath(IvrSession session, TokenPath newPath) {
        Set<TokenType> keep = new HashSet<>(newPath.getRequiredTokens());
        session.getValidatedTokens().retainAll(keep);
    }

    private boolean isLocked(IvrSession session) {
        return session.getStatus() == SessionStatus.LOCKED
            && session.getLockedUntil() != null
            && Instant.now().isBefore(session.getLockedUntil());
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

    private SessionResponse buildLockedResponse(IvrSession session) {
        return SessionResponse.builder()
            .sessionId(session.getSessionId())
            .status(SessionStatus.LOCKED)
            .currentLevel(session.getCurrentLevel())
            .targetLevel(session.getTargetLevel())
            .lockedUntil(session.getLockedUntil())
            .prompt("Your account has been temporarily locked due to too many failed attempts.")
            .build();
    }
}