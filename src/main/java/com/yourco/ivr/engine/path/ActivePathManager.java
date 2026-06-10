package com.yourco.ivr.engine.path;

import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.api.dto.ProcessingEvent;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.LevelRule;
import com.yourco.ivr.domain.config.TokenPath;
import com.yourco.ivr.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static com.yourco.ivr.engine.response.ResponseAssembler.*;

/**
 * Manages navigation through the ordered list of fallback paths for a given
 * authentication level. Owns path resolution, switching, and exhaustion logic.
 */
@Component
public class ActivePathManager {

    private static final Logger log = LoggerFactory.getLogger(ActivePathManager.class);

    private final SessionRepository sessionRepo;

    public ActivePathManager(SessionRepository sessionRepo) {
        this.sessionRepo = sessionRepo;
    }

    public ActivePath resolveActivePath(IvrSession session, BrandAuthConfig config) {
        LevelRule rule = config.getLevelRules() != null
            ? config.getLevelRules().get(session.getTargetLevel()) : null;
        int index = session.getActivePathIndexByLevel().getOrDefault(session.getTargetLevel(), 0);
        TokenPath path = (rule != null && index < rule.getPaths().size())
            ? rule.getPaths().get(index) : null;
        return new ActivePath(rule, index, path);
    }

    public static LevelRule levelRule(BrandAuthConfig config, IvrSession session) {
        return config.getLevelRules() != null
            ? config.getLevelRules().get(session.getTargetLevel()) : null;
    }

    public AuthenticateResponse switchToFallbackPath(IvrSession session,
                                                      BrandAuthConfig config,
                                                      LevelRule rule,
                                                      List<ProcessingEvent> procLog,
                                                      BiFunction<IvrSession, BrandAuthConfig, AuthenticateResponse> evaluator) {
        Map<AuthLevel, Integer> pathIndexMap = session.getActivePathIndexByLevel();
        int nextPathIdx = pathIndexMap.getOrDefault(session.getTargetLevel(), 0) + 1;
        if (nextPathIdx >= rule.getPaths().size()) {
            return null;
        }
        TokenPath newPath = rule.getPaths().get(nextPathIdx);

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
        return evaluator.apply(session, config);
    }

    public AuthenticateResponse advanceToNextPathOrFail(IvrSession session, BrandAuthConfig config,
                                                        LevelRule rule, int currentPathIdx,
                                                        BiFunction<IvrSession, BrandAuthConfig, AuthenticateResponse> evaluator) {
        Map<AuthLevel, Integer> pathIndexMap = session.getActivePathIndexByLevel();
        int nextPathIdx = currentPathIdx + 1;

        if (nextPathIdx < rule.getPaths().size()) {
            pathIndexMap.put(session.getTargetLevel(), nextPathIdx);
            session.getAttemptCounts().clear();
            sessionRepo.save(session);
            return evaluator.apply(session, config);
        }

        session.setStatus(SessionStatus.FAILED);
        sessionRepo.save(session);
        return failed(session, "Authentication failed. No available authentication methods for your account.");
    }

    public AuthenticateResponse handleRedirectToAgent(IvrSession session,
                                                       LevelRule rule,
                                                       List<ProcessingEvent> procLog) {
        addEntry(procLog, "FAIL", "Redirect to agent after " + rule.getLockoutSeconds() + " seconds");

        session.setStatus(SessionStatus.REDIRECT_TO_AGENT);
        session.setLockedUntil(Instant.now().plusSeconds(rule.getLockoutSeconds()));
        sessionRepo.save(session);
        log.warn("AUTH [{}] brand={} caller={} REDIRECT_TO_AGENT for {} seconds",
            session.getSessionId(), session.getBrandId(), session.getCallerId(),
            rule.getLockoutSeconds());
        return redirectToAgent(session, session.getLockedUntil(),
            "Authentication failed. All retry attempts exhausted. Redirecting to agent.");
    }

    private static void addEntry(List<ProcessingEvent> procLog, String level, String message) {
        procLog.add(ProcessingEvent.builder().level(level).message(message).build());
    }
}