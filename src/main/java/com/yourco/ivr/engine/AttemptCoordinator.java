package com.yourco.ivr.engine;

import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.api.dto.ProcessingEvent;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.LevelRule;
import com.yourco.ivr.domain.config.TokenPath;
import com.yourco.ivr.engine.path.ActivePathManager;
import com.yourco.ivr.engine.preference.PreferenceFilter;
import com.yourco.ivr.engine.slot.TokenSlotResolver;
import com.yourco.ivr.repository.SessionRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static com.yourco.ivr.engine.response.ProcessingLog.add;
import static com.yourco.ivr.engine.response.ResponseAssembler.collecting;

/**
 * Owns the retry / attempt-exhaustion side of token collection: counting failed attempts against
 * a slot, re-prompting while attempts remain, and deciding the terminal behaviour once they run
 * out (switch to a fallback path, or redirect to an agent).
 *
 * <p>Extracted from {@link AuthEngine} so the engine's state-machine flow stays readable as more
 * failure edge-cases accumulate. The {@code evaluator} argument is {@link AuthEngine#evaluateProgress}
 * passed as a method reference — the same indirection {@link ActivePathManager} already uses to
 * re-enter progress evaluation after a path switch.
 */
@Component
public class AttemptCoordinator {

    private final SessionRepository sessionRepo;
    private final TokenSlotResolver slotResolver;
    private final PromptResolver promptResolver;
    private final ActivePathManager pathManager;

    public AttemptCoordinator(SessionRepository sessionRepo,
                              TokenSlotResolver slotResolver,
                              PromptResolver promptResolver,
                              ActivePathManager pathManager) {
        this.sessionRepo = sessionRepo;
        this.slotResolver = slotResolver;
        this.promptResolver = promptResolver;
        this.pathManager = pathManager;
    }

    /**
     * Handles a failed external validation. Re-prompts if attempts remain; once exhausted, tries
     * the next fallback path before giving up and redirecting to an agent.
     */
    public AuthenticateResponse onValidationFailure(IvrSession session, BrandAuthConfig config,
                                                    TokenType tokenType, List<ProcessingEvent> procLog,
                                                    BiFunction<IvrSession, BrandAuthConfig, AuthenticateResponse> evaluator) {
        AuthenticateResponse reprompt = recordAttemptOrExhaust(session, config, tokenType, procLog);
        if (reprompt != null) return reprompt;

        LevelRule rule = ActivePathManager.levelRule(config, session);
        AuthenticateResponse switched = pathManager.switchToFallbackPath(session, config, rule, procLog, evaluator);
        if (switched != null) return switched;
        return pathManager.handleRedirectToAgent(session, rule, procLog);
    }

    /**
     * Handles a submission of the wrong token type for the active slot. Re-prompts while attempts
     * remain; once exhausted, redirects to an agent without switching paths (a wrong type is a
     * caller error, not a reason to abandon the configured path).
     */
    public AuthenticateResponse onWrongTypeFailure(IvrSession session, BrandAuthConfig config,
                                                   TokenType tokenType, List<ProcessingEvent> procLog) {
        AuthenticateResponse reprompt = recordAttemptOrExhaust(session, config, tokenType, procLog);
        if (reprompt != null) return reprompt;

        LevelRule rule = ActivePathManager.levelRule(config, session);
        add(procLog, "FAIL", "Wrong token type exhausted retries — redirecting to agent (no path switch)");
        return pathManager.handleRedirectToAgent(session, rule, procLog);
    }

    /**
     * Records a failed attempt against the submitted token's required slot. Returns a "still
     * collecting" reprompt response if attempts remain, or {@code null} once the retry limit is
     * exhausted (leaving the caller to decide the terminal behavior).
     */
    private AuthenticateResponse recordAttemptOrExhaust(IvrSession session, BrandAuthConfig config,
                                                        TokenType tokenType, List<ProcessingEvent> procLog) {
        LevelRule rule = ActivePathManager.levelRule(config, session);
        TokenType requiredToken = slotResolver.findRequiredTokenForSlot(session, config, tokenType);
        int remaining = recordFailedAttempt(session, rule, requiredToken, procLog);
        if (remaining > 0) {
            return repromptForSlot(session, rule, requiredToken, remaining, procLog);
        }
        add(procLog, "WARN", "Retry limit exhausted for " + requiredToken + " slot");
        return null;
    }

    private static int recordFailedAttempt(IvrSession session, LevelRule rule,
                                           TokenType requiredToken, List<ProcessingEvent> procLog) {
        Map<TokenType, Integer> counts = session.getAttemptCounts();
        int attempts = counts.containsKey(requiredToken) ? counts.get(requiredToken) + 1 : 1;
        counts.put(requiredToken, attempts);
        int maxRetries = rule.getMaxRetriesFor(requiredToken);

        add(procLog, "WARN", "Attempt " + attempts + " of " + maxRetries + " for " + requiredToken + " slot");
        return maxRetries - attempts;
    }

    private AuthenticateResponse repromptForSlot(IvrSession session, LevelRule rule,
                                                 TokenType requiredToken, int remaining,
                                                 List<ProcessingEvent> procLog) {
        add(procLog, "WARN", remaining + " attempt" + (remaining == 1 ? "" : "s")
            + " remaining — still collecting " + requiredToken);

        int activePathIdx = session.getActivePathIndexByLevel()
            .getOrDefault(session.getTargetLevel(), 0);
        TokenPath activePath = rule.getPaths().get(activePathIdx);

        List<TokenType> acceptedTokens = slotResolver.buildAcceptedTokens(session, activePath,
            requiredToken, requiredToken, t -> PreferenceFilter.isBlocked(session, t));
        String prompt = promptResolver.resolvePrompt(requiredToken, activePath, remaining);
        sessionRepo.save(session);
        return collecting(session, requiredToken, acceptedTokens, remaining, prompt);
    }
}
