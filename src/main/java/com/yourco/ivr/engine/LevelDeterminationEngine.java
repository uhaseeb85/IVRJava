package com.yourco.ivr.engine;

import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.Party;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.LevelCondition;
import com.yourco.ivr.domain.config.LevelConditionType;
import com.yourco.ivr.domain.config.LevelDeterminationConfig;
import com.yourco.ivr.domain.config.LevelSelectionRule;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Derives the session's target {@link AuthLevel} from the brand's declarative
 * {@code levelDetermination} rules instead of relying on a caller-supplied target level.
 *
 * <p>Evaluation runs once the caller's party has been resolved:
 * <ol>
 *   <li>Walk {@code levelDetermination.rules} in order; the first rule whose conditions
 *       all pass selects the level. Rules with an empty condition list always match.</li>
 *   <li>If no rule matches, fall back to {@code levelDetermination.defaultLevel}.</li>
 *   <li>The result (level + human-readable reason) is then applied by
 *       {@link com.yourco.ivr.service.AuthenticateService}, which also clamps it to the
 *       customer's {@code maxAllowedLevel} preference.</li>
 * </ol>
 *
 * <p>Returns {@code null} when the brand defines no {@code levelDetermination} section —
 * the caller's requested level is then used unchanged (legacy behavior).
 *
 * <p>Reasons never include party-supplied values (only the brand-config condition
 * constants), so no sensitive data leaks into the processing log.
 */
@Service
public class LevelDeterminationEngine {

    /** Outcome of rule evaluation: the selected level plus a log-safe reason. */
    @Data
    @RequiredArgsConstructor
    public static class DeterminationResult {
        private final AuthLevel level;
        private final String reason;
    }

    /**
     * Evaluates the brand's level-determination rules against the resolved party.
     *
     * @return the derived level + reason, or {@code null} if the brand has no
     *         {@code levelDetermination} config (or no rule matched and no default exists)
     */
    public DeterminationResult determine(BrandAuthConfig config, Party party) {
        LevelDeterminationConfig det = config.getLevelDetermination();
        if (det == null) {
            return null;
        }

        List<LevelSelectionRule> rules = det.getRules() != null
            ? det.getRules() : Collections.emptyList();
        for (int i = 0; i < rules.size(); i++) {
            LevelSelectionRule rule = rules.get(i);
            if (rule == null || !matches(rule, party)) {
                continue;
            }
            return new DeterminationResult(rule.getLevel(), describeRule(i, rule));
        }

        if (det.getDefaultLevel() != null) {
            return new DeterminationResult(det.getDefaultLevel(),
                "no rule matched; brand default level " + det.getDefaultLevel());
        }
        return null;
    }

    private static boolean matches(LevelSelectionRule rule, Party party) {
        if (rule.getConditions() == null || rule.getConditions().isEmpty()) {
            return true;
        }
        for (LevelCondition condition : rule.getConditions()) {
            if (condition == null || !matches(condition, party)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(LevelCondition condition, Party party) {
        if (condition.getType() == null) {
            return false;
        }
        switch (condition.getType()) {
            case ANI_MATCHED:
                return party != null;
            case PARTY_ACTIVE:
                return party != null && party.isActive();
            case PRIMARY_ANI:
                return party != null && party.isPrimaryAni();
            case PARTY_ATTRIBUTE:
                return party != null
                    && Objects.equals(party.getAttribute(condition.getKey()), condition.getValue());
            default:
                return false;
        }
    }

    private static String describeRule(int index, LevelSelectionRule rule) {
        if (rule.getDescription() != null && !rule.getDescription().trim().isEmpty()) {
            return "rule \"" + rule.getDescription() + "\"";
        }
        return "rule #" + index + " (" + describeConditions(rule.getConditions()) + ")";
    }

    private static String describeConditions(List<LevelCondition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return "always matches";
        }
        return conditions.stream()
            .map(LevelDeterminationEngine::describeCondition)
            .collect(Collectors.joining(" AND "));
    }

    private static String describeCondition(LevelCondition condition) {
        if (condition.getType() == LevelConditionType.PARTY_ATTRIBUTE) {
            return condition.getType() + " " + condition.getKey() + "=" + condition.getValue();
        }
        return String.valueOf(condition.getType());
    }
}
