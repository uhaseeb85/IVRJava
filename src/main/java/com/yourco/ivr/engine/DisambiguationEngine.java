package com.yourco.ivr.engine;

import com.yourco.ivr.api.dto.SessionResponse;
import com.yourco.ivr.domain.*;
import com.yourco.ivr.domain.config.DisambiguationConfig;
import com.yourco.ivr.engine.impl.ExcludeInactiveRule;
import com.yourco.ivr.engine.impl.PrimaryAniRule;
import com.yourco.ivr.preference.CustomerPreferenceProvider;
import com.yourco.ivr.repository.SessionRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DisambiguationEngine {

    private static final Map<TokenType, Function<Party, String>> TOKEN_FIELD_MAP = new LinkedHashMap<>();

    static {
        TOKEN_FIELD_MAP.put(TokenType.ACCOUNT_NUMBER, Party::getAccountNumber);
        TOKEN_FIELD_MAP.put(TokenType.DATE_OF_BIRTH, Party::getDateOfBirth);
        TOKEN_FIELD_MAP.put(TokenType.SSN_LAST4, Party::getSsnLast4);
        TOKEN_FIELD_MAP.put(TokenType.CARD_LAST4, Party::getCardLast4);
    }

    private final SessionRepository sessionRepo;
    private final CustomerPreferenceProvider preferenceProvider;
    private final PromptResolver promptResolver;

    public DisambiguationEngine(SessionRepository sessionRepo,
                                 CustomerPreferenceProvider preferenceProvider,
                                 PromptResolver promptResolver) {
        this.sessionRepo = sessionRepo;
        this.preferenceProvider = preferenceProvider;
        this.promptResolver = promptResolver;
    }

    public SessionResponse start(IvrSession session, DisambiguationConfig config) {
        // 1. Apply configured rules
        List<Party> parties = applyRules(session.getCandidateParties(), config);

        // 2. Check result
        if (parties.isEmpty()) {
            session.setStatus(SessionStatus.FAILED);
            sessionRepo.save(session);
            return buildResponse(session, "No matching parties found after applying disambiguation rules.", null);
        }

        if (parties.size() == 1) {
            return resolveParty(session, parties.get(0));
        }

        // 3. Still multiple parties — pick best differentiating token
        session.setCandidateParties(parties);
        TokenType nextToken = selectDisambiguationToken(parties);

        if (nextToken == null) {
            session.setStatus(SessionStatus.FAILED);
            sessionRepo.save(session);
            return buildResponse(session, "Unable to disambiguate parties with available tokens.", null);
        }

        session.setDisambiguationAttemptCount(1);
        sessionRepo.save(session);

        String prompt = "Please provide your " + formatTokenName(nextToken) + " to verify your identity.";
        return buildResponse(session, prompt, nextToken);
    }

    public SessionResponse handleToken(IvrSession session, TokenType tokenType,
                                        String tokenValue, DisambiguationConfig config) {
        // 1. Verify token is usable for disambiguation
        if (!TOKEN_FIELD_MAP.containsKey(tokenType)) {
            return buildResponse(session,
                "The provided token type cannot be used for disambiguation. Please try another.",
                selectDisambiguationToken(session.getCandidateParties()));
        }

        // 2. Check max rounds
        if (session.getDisambiguationAttemptCount() >= config.getMaxDisambiguationTokens()) {
            session.setStatus(SessionStatus.FAILED);
            sessionRepo.save(session);
            return buildResponse(session, "Maximum disambiguation attempts exceeded.", null);
        }

        // 3. Match token value against remaining parties
        List<Party> remaining = session.getCandidateParties();
        Function<Party, String> extractor = TOKEN_FIELD_MAP.get(tokenType);

        List<Party> matching = remaining.stream()
            .filter(p -> tokenValue.equals(extractor.apply(p)))
            .collect(Collectors.toList());

        if (matching.isEmpty()) {
            return buildResponse(session,
                "The provided value does not match any known party. Please try again.",
                tokenType);
        }

        if (matching.size() == 1) {
            return resolveParty(session, matching.get(0));
        }

        // 4. Still multiple — pick next differentiating token
        session.setCandidateParties(matching);
        session.setDisambiguationAttemptCount(session.getDisambiguationAttemptCount() + 1);

        if (session.getDisambiguationAttemptCount() >= config.getMaxDisambiguationTokens()) {
            session.setStatus(SessionStatus.FAILED);
            sessionRepo.save(session);
            return buildResponse(session, "Maximum disambiguation attempts exceeded.", null);
        }

        TokenType nextToken = selectDisambiguationToken(matching);
        if (nextToken == null) {
            session.setStatus(SessionStatus.FAILED);
            sessionRepo.save(session);
            return buildResponse(session, "Unable to further disambiguate parties.", null);
        }

        sessionRepo.save(session);

        String prompt = "Please provide your " + formatTokenName(nextToken) + " to verify your identity.";
        return buildResponse(session, prompt, nextToken);
    }

    TokenType selectDisambiguationToken(List<Party> parties) {
        TokenType bestToken = null;
        int bestMaxGroupSize = Integer.MAX_VALUE;

        for (Map.Entry<TokenType, Function<Party, String>> entry : TOKEN_FIELD_MAP.entrySet()) {
            TokenType tokenType = entry.getKey();
            Function<Party, String> extractor = entry.getValue();

            Map<String, Long> counts = parties.stream()
                .collect(Collectors.groupingBy(
                    p -> extractor.apply(p) != null ? extractor.apply(p) : "__null__",
                    Collectors.counting()));

            int maxGroupSize = counts.values().stream()
                .mapToInt(Long::intValue)
                .max()
                .orElse(parties.size());

            if (maxGroupSize < bestMaxGroupSize) {
                bestMaxGroupSize = maxGroupSize;
                bestToken = tokenType;
            }
        }

        return bestToken;
    }

    List<Party> applyRules(List<Party> parties, DisambiguationConfig config) {
        if (config.getRules() == null || config.getRules().isEmpty()) {
            return parties;
        }

        List<Party> result = new ArrayList<>(parties);
        for (DisambiguationConfig.DisambiguationRuleConfig ruleConfig : config.getRules()) {
            DisambiguationRule rule = createRule(ruleConfig.getType());
            if (rule != null) {
                result = rule.apply(result);
            }
        }
        return result;
    }

    String formatTokenName(TokenType tokenType) {
        switch (tokenType) {
            case ACCOUNT_NUMBER:
                return "account number";
            case DATE_OF_BIRTH:
                return "date of birth (YYYY-MM-DD)";
            case SSN_LAST4:
                return "last 4 digits of your SSN";
            case CARD_LAST4:
                return "last 4 digits of your card";
            default:
                return tokenType.name().toLowerCase().replace('_', ' ');
        }
    }

    private DisambiguationRule createRule(String type) {
        switch (type) {
            case "EXCLUDE_INACTIVE":
                return new ExcludeInactiveRule();
            case "PREFER_PRIMARY_ANI":
                return new PrimaryAniRule();
            default:
                return null;
        }
    }

    private SessionResponse resolveParty(IvrSession session, Party party) {
        session.setMatchedParty(party);
        session.setCandidateParties(Collections.singletonList(party));
        session.setPhase(SessionPhase.AUTHENTICATING);

        CustomerPreference prefs = preferenceProvider.getPreferences(
            party.getPartyId(), session.getBrandId());
        session.setCustomerPreferences(prefs);

        sessionRepo.save(session);
        return buildResponse(session,
            "Identity verified. Proceeding with authentication.", null);
    }

    private SessionResponse buildResponse(IvrSession session, String prompt, TokenType nextToken) {
        return SessionResponse.builder()
            .sessionId(session.getSessionId())
            .status(session.getStatus())
            .phase(session.getPhase())
            .currentLevel(session.getCurrentLevel())
            .targetLevel(session.getTargetLevel())
            .nextRequiredToken(nextToken)
            .prompt(prompt)
            .matchedPartyId(session.getMatchedParty() != null
                ? session.getMatchedParty().getPartyId() : null)
            .build();
    }
}
