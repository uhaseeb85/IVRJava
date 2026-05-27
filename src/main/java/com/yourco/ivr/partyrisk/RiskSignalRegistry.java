package com.yourco.ivr.partyrisk;

import com.yourco.ivr.domain.CompositeRiskAssessment;
import com.yourco.ivr.domain.RiskAssessment;
import com.yourco.ivr.domain.RiskLevel;
import com.yourco.ivr.domain.config.MatrixRule;
import com.yourco.ivr.domain.config.RiskCombinationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-discovers all {@link RiskSignalProvider} beans and combines their outputs
 * into a single {@link CompositeRiskAssessment}.
 *
 * <p>To add a new risk dimension, implement {@link RiskSignalProvider} (or a
 * sub-interface like {@link PhoneRiskProvider}) and annotate the class with
 * {@code @Component}. Nothing else needs to change — Spring injects the full
 * list here automatically.</p>
 */
@Component
public class RiskSignalRegistry {

    private static final Logger log = LoggerFactory.getLogger(RiskSignalRegistry.class);

    private final List<RiskSignalProvider> providers;

    public RiskSignalRegistry(List<RiskSignalProvider> providers) {
        this.providers = providers;
        log.debug("Risk signal providers registered: {}",
            providers.stream().map(RiskSignalProvider::providerId).toList());
    }

    /**
     * Call every registered provider, then apply the brand's combination strategy
     * to produce a single final {@link RiskLevel}.
     *
     * @param callerId   ANI of the caller
     * @param brandId    brand context
     * @param combConfig brand's {@code riskCombination} block, or {@code null}
     *                   to use the default MAX strategy
     * @return composite assessment with final level + per-provider signal breakdown
     */
    public CompositeRiskAssessment evaluate(String callerId, String brandId,
                                            RiskCombinationConfig combConfig) {
        // ── Collect signals from every provider ──────────────────────────────
        Map<String, RiskLevel> signals = new LinkedHashMap<>();
        List<String> allFlags = new ArrayList<>();

        for (RiskSignalProvider provider : providers) {
            RiskAssessment result = provider.assess(callerId, brandId);
            signals.put(provider.providerId(), result.getLevel());
            if (result.getFlags() != null) {
                allFlags.addAll(result.getFlags());
            }
        }

        // ── Combine signals into a single level ──────────────────────────────
        RiskLevel finalLevel = computeFinalLevel(signals, combConfig);

        log.debug("Risk evaluation for caller={} brand={}: signals={} → final={}",
            callerId, brandId, signals, finalLevel);

        // ── Build composite result ───────────────────────────────────────────
        CompositeRiskAssessment composite = new CompositeRiskAssessment();
        composite.setLevel(finalLevel);
        composite.setFlags(allFlags);
        composite.setSignals(signals);
        return composite;
    }

    // ── Combination strategies ────────────────────────────────────────────────

    private RiskLevel computeFinalLevel(Map<String, RiskLevel> signals,
                                        RiskCombinationConfig config) {
        if (config != null
                && "MATRIX".equals(config.getStrategy())
                && config.getMatrixRules() != null) {
            // Try matrix rules first; fall through to MAX if none match
            for (MatrixRule rule : config.getMatrixRules()) {
                if (ruleMatches(rule.getConditions(), signals)) {
                    return rule.getResult();
                }
            }
        }
        return maxLevel(signals);
    }

    /**
     * MAX strategy — take the highest level across all signals.
     */
    private static RiskLevel maxLevel(Map<String, RiskLevel> signals) {
        return signals.values().stream()
            .max(Comparator.comparingInt(RiskLevel::ordinal))
            .orElse(RiskLevel.LOW);
    }

    /**
     * MATRIX rule match — ALL conditions must be satisfied (AND logic).
     * Comparison is at-or-above: a condition of HIGH matches HIGH or CRITICAL.
     */
    private static boolean ruleMatches(Map<String, RiskLevel> conditions,
                                       Map<String, RiskLevel> signals) {
        for (Map.Entry<String, RiskLevel> condition : conditions.entrySet()) {
            RiskLevel actual = signals.get(condition.getKey());
            // null signal = provider not registered → condition fails
            if (actual == null || actual.ordinal() < condition.getValue().ordinal()) {
                return false;
            }
        }
        return true;
    }
}
