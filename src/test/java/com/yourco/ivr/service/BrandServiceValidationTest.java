package com.yourco.ivr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.LevelCondition;
import com.yourco.ivr.domain.config.LevelConditionType;
import com.yourco.ivr.domain.config.LevelDeterminationConfig;
import com.yourco.ivr.domain.config.LevelRule;
import com.yourco.ivr.domain.config.LevelSelectionRule;
import com.yourco.ivr.domain.config.TokenPath;
import com.yourco.ivr.registry.BrandRulesRegistry;
import com.yourco.ivr.validator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BrandService#validate(BrandAuthConfig)} focusing on the
 * {@code levelDetermination} section: rule/default levels must exist in {@code levelRules},
 * conditions must declare a supported type, and {@code PARTY_ATTRIBUTE} conditions must
 * declare a key.
 */
class BrandServiceValidationTest {

    private final BrandService brandService =
        new BrandService(new BrandRulesRegistry(), new ObjectMapper(), "./config/brands");

    /** Minimal valid brand config with BASIC + STANDARD level rules. */
    private static BrandAuthConfig validBaseConfig() {
        BrandAuthConfig config = new BrandAuthConfig();
        config.setBrandId("VALIDATION_TEST");
        config.setLevelRules(levelRules(AuthLevel.BASIC, AuthLevel.STANDARD));
        return config;
    }

    private static Map<AuthLevel, LevelRule> levelRules(AuthLevel... levels) {
        Map<AuthLevel, LevelRule> rules = new LinkedHashMap<>();
        for (AuthLevel level : levels) {
            TokenPath path = new TokenPath();
            path.setPathIndex(0);
            path.setRequiredTokens(Collections.singletonList(
                level == AuthLevel.BASIC
                    ? com.yourco.ivr.domain.TokenType.ACCOUNT_NUMBER
                    : com.yourco.ivr.domain.TokenType.PIN));
            LevelRule rule = new LevelRule();
            rule.setPaths(Collections.singletonList(path));
            rule.setMaxRetriesPerToken(3);
            rules.put(level, rule);
        }
        return rules;
    }

    private static LevelCondition attrCondition(String key, String value) {
        LevelCondition condition = new LevelCondition();
        condition.setType(LevelConditionType.PARTY_ATTRIBUTE);
        condition.setKey(key);
        condition.setValue(value);
        return condition;
    }

    private static LevelSelectionRule rule(AuthLevel level, LevelCondition... conditions) {
        LevelSelectionRule rule = new LevelSelectionRule();
        rule.setLevel(level);
        rule.setConditions(conditions.length == 0 ? Collections.emptyList() : Arrays.asList(conditions));
        return rule;
    }

    @Test
    void validLevelDeterminationIsAccepted() {
        LevelDeterminationConfig det = new LevelDeterminationConfig();
        det.setRules(Collections.singletonList(rule(AuthLevel.STANDARD, attrCondition("segment", "PREMIUM"))));
        det.setDefaultLevel(AuthLevel.BASIC);

        BrandAuthConfig config = validBaseConfig();
        config.setLevelDetermination(det);

        ValidationResult result = brandService.validate(config);
        assertTrue(result.isValid(), result.getMessage());
    }

    @Test
    void ruleWithEmptyConditionsAndDefaultLevelOnlyIsAccepted() {
        LevelDeterminationConfig det = new LevelDeterminationConfig();
        det.setRules(Collections.singletonList(rule(AuthLevel.STANDARD))); // empty conditions = always match
        det.setDefaultLevel(AuthLevel.BASIC);

        BrandAuthConfig config = validBaseConfig();
        config.setLevelDetermination(det);

        ValidationResult result = brandService.validate(config);
        assertTrue(result.isValid(), result.getMessage());
    }

    @Test
    void sectionWithNoRulesAndNoDefaultLevelIsRejected() {
        LevelDeterminationConfig det = new LevelDeterminationConfig();
        det.setRules(Collections.emptyList());
        det.setDefaultLevel(null);

        BrandAuthConfig config = validBaseConfig();
        config.setLevelDetermination(det);

        assertFalse(brandService.validate(config).isValid());
    }

    @Test
    void rulesWithoutDefaultLevelAreRejected() {
        // A rules-only section can 500 at runtime when no rule matches (the session would
        // be left at NONE with no NONE rule) — a defaultLevel must always be present.
        LevelDeterminationConfig det = new LevelDeterminationConfig();
        det.setRules(Collections.singletonList(rule(AuthLevel.STANDARD, attrCondition("segment", "PREMIUM"))));
        det.setDefaultLevel(null);

        BrandAuthConfig config = validBaseConfig();
        config.setLevelDetermination(det);

        assertFalse(brandService.validate(config).isValid());
    }

    @Test
    void attributeConditionWithoutValueIsRejected() {
        // A PARTY_ATTRIBUTE condition without a value silently matches every party that
        // lacks the attribute — an unintended catch-all — so it must be rejected.
        LevelCondition condition = new LevelCondition();
        condition.setType(LevelConditionType.PARTY_ATTRIBUTE);
        condition.setKey("segment");

        LevelDeterminationConfig det = new LevelDeterminationConfig();
        det.setRules(Collections.singletonList(rule(AuthLevel.STANDARD, condition)));
        det.setDefaultLevel(AuthLevel.BASIC);

        BrandAuthConfig config = validBaseConfig();
        config.setLevelDetermination(det);

        assertFalse(brandService.validate(config).isValid());
    }

    @Test
    void nonMonotonicLevelRulesAreRejected() {
        // A higher level must add at least one token not required by lower levels,
        // otherwise escalation to it would be a free privilege promotion.
        BrandAuthConfig config = validBaseConfig();
        config.setLevelRules(levelRules(AuthLevel.BASIC, AuthLevel.STANDARD));
        // Force STANDARD to reuse BASIC's token set:
        config.getLevelRules().get(AuthLevel.STANDARD).getPaths().get(0)
            .setRequiredTokens(Collections.singletonList(com.yourco.ivr.domain.TokenType.ACCOUNT_NUMBER));

        assertFalse(brandService.validate(config).isValid());
    }

    @Test
    void ruleLevelNotDefinedInLevelRulesIsRejected() {
        LevelDeterminationConfig det = new LevelDeterminationConfig();
        det.setRules(Collections.singletonList(rule(AuthLevel.ELEVATED, attrCondition("segment", "PREMIUM"))));
        det.setDefaultLevel(AuthLevel.BASIC);

        BrandAuthConfig config = validBaseConfig();
        config.setLevelDetermination(det);

        assertFalse(brandService.validate(config).isValid());
    }

    @Test
    void defaultLevelNotDefinedInLevelRulesIsRejected() {
        LevelDeterminationConfig det = new LevelDeterminationConfig();
        det.setRules(Collections.singletonList(rule(AuthLevel.STANDARD)));
        det.setDefaultLevel(AuthLevel.ELEVATED);

        BrandAuthConfig config = validBaseConfig();
        config.setLevelDetermination(det);

        assertFalse(brandService.validate(config).isValid());
    }

    @Test
    void attributeConditionWithoutKeyIsRejected() {
        LevelDeterminationConfig det = new LevelDeterminationConfig();
        det.setRules(Collections.singletonList(rule(AuthLevel.STANDARD, attrCondition(null, "PREMIUM"))));
        det.setDefaultLevel(AuthLevel.BASIC);

        BrandAuthConfig config = validBaseConfig();
        config.setLevelDetermination(det);

        assertFalse(brandService.validate(config).isValid());
    }

    @Test
    void conditionWithoutTypeIsRejected() {
        LevelCondition condition = new LevelCondition();
        condition.setKey("segment");
        condition.setValue("PREMIUM");

        LevelDeterminationConfig det = new LevelDeterminationConfig();
        det.setRules(Collections.singletonList(rule(AuthLevel.STANDARD, condition)));
        det.setDefaultLevel(AuthLevel.BASIC);

        BrandAuthConfig config = validBaseConfig();
        config.setLevelDetermination(det);

        assertFalse(brandService.validate(config).isValid());
    }

    @Test
    void legacyConfigWithoutLevelDeterminationStillValidates() {
        assertTrue(brandService.validate(validBaseConfig()).isValid());
    }
}
