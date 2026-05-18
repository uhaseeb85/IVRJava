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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

        // Store the collected token value
        session.getCollectedTokens().put(tokenType, tokenValue);

        // 1. Check cross-brand token shortcut before calling external API
        boolean valid = crossBrandEvaluator.isAccepted(session, tokenType)
            || validateExternally(session, tokenType, tokenValue);

        if (!valid) {
            return handleFailure(session, config, tokenType);
        }

        // 2. Map backup token to the required token if applicable
        TokenType resolvedToken = resolveBackupToken(session, config, tokenType);
        session.getValidatedTokens().add(resolvedToken);
        session.getAttemptCounts().remove(tokenType);
        crossBrandEvaluator.recordValidated(session, tokenType);

        // 3. Evaluate progress toward targetLevel
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
            // All paths exhausted — fail
            session.setStatus(SessionStatus.FAILED);
            sessionRepo.save(session);
            return SessionResponse.builder()
                .sessionId(session.getSessionId())
                .status(SessionStatus.FAILED)
                .currentLevel(session.getCurrentLevel())
                .targetLevel(session.getTargetLevel())
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
            return SessionResponse.builder()
                .sessionId(session.getSessionId())
                .status(SessionStatus.AUTHENTICATED)
                .currentLevel(session.getCurrentLevel())
                .targetLevel(session.getTargetLevel())
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
            return SessionResponse.builder()
                .sessionId(session.getSessionId())
                .status(SessionStatus.AUTHENTICATED)
                .currentLevel(session.getCurrentLevel())
                .targetLevel(session.getTargetLevel())
                .build();
        }

        session.setStatus(SessionStatus.COLLECTING);
        sessionRepo.save(session);

        // Determine accepted tokens for this step (required token + any backups)
        List<TokenType> acceptedTokens = buildAcceptedTokens(activePath, nextToken);

        String prompt = promptResolver.resolvePrompt(nextToken, activePath, rule.getMaxRetriesPerToken());
        return SessionResponse.builder()
            .sessionId(session.getSessionId())
            .status(SessionStatus.COLLECTING)
            .currentLevel(session.getCurrentLevel())
            .targetLevel(session.getTargetLevel())
            .nextRequiredToken(nextToken)
            .remainingAttempts(rule.getMaxRetriesPerToken())
            .acceptedTokens(acceptedTokens)
            .prompt(prompt)
            .build();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

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

            List<TokenType> acceptedTokens = buildAcceptedTokens(nextPath, nextToken);
            String prompt = promptResolver.resolvePrompt(nextToken, nextPath, rule.getMaxRetriesPerToken());
            return SessionResponse.builder()
                .sessionId(session.getSessionId())
                .status(SessionStatus.COLLECTING)
                .currentLevel(session.getCurrentLevel())
                .targetLevel(session.getTargetLevel())
                .nextRequiredToken(nextToken)
                .remainingAttempts(rule.getMaxRetriesPerToken())
                .acceptedTokens(acceptedTokens)
                .prompt("Fallback: " + prompt)
                .build();
        }

        // All paths exhausted — lockout
        session.setStatus(SessionStatus.LOCKED);
        session.setLockedUntil(Instant.now().plusSeconds(rule.getLockoutSeconds()));
        sessionRepo.save(session);
        return SessionResponse.builder()
            .sessionId(session.getSessionId())
            .status(SessionStatus.LOCKED)
            .currentLevel(session.getCurrentLevel())
            .targetLevel(session.getTargetLevel())
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
     * Includes the required token plus any backup alternatives.
     */
    private List<TokenType> buildAcceptedTokens(TokenPath activePath, TokenType nextToken) {
        List<TokenType> accepted = new ArrayList<>();
        accepted.add(nextToken);
        if (activePath.getBackupTokens() != null) {
            List<TokenType> backups = activePath.getBackupTokens().get(nextToken);
            if (backups != null) {
                accepted.addAll(backups);
            }
        }
        return accepted;
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