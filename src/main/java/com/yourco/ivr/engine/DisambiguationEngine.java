package com.yourco.ivr.engine;

import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.domain.*;
import com.yourco.ivr.domain.config.DisambiguationConfig;
import com.yourco.ivr.engine.impl.ExcludeInactiveRule;
import com.yourco.ivr.engine.impl.PrimaryAniRule;
import com.yourco.ivr.preference.CustomerPreferenceProvider;
import com.yourco.ivr.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves ambiguous party lookups by iteratively prompting the caller for identifying tokens.
 *
 * <p>When an ANI lookup returns multiple {@link com.yourco.ivr.domain.Party} records,
 * this engine is responsible for narrowing the list to a single match before authentication
 * can proceed. The flow is:
 * <ol>
 *   <li>{@link #start} — applies configured pre-filter rules (e.g. exclude inactive parties,
 *       prefer primary ANI), then selects the best differentiating token type to prompt.</li>
 *   <li>{@link #handleToken} — on each subsequent submission, narrows the candidate list by
 *       matching the token value against the remaining parties' corresponding fields.</li>
 *   <li>Once a single party remains, the session transitions to
 *       {@link com.yourco.ivr.domain.SessionPhase#AUTHENTICATING} and authentication begins.</li>
 * </ol>
 *
 * <p>Token-to-field mapping is fixed at construction ({@link #defaultTokenFieldMap}) and
 * covers the four PII fields most useful for disambiguation:
 * {@code ACCOUNT_NUMBER}, {@code DATE_OF_BIRTH}, {@code SSN_LAST4}, {@code CARD_LAST4}.
 *
 * <p>The engine selects the next token type using a greedy max-information heuristic:
 * it picks the token whose values, across the current candidate list, produce the smallest
 * maximum group size — i.e. the token that most evenly splits the parties.
 *
 * @see com.yourco.ivr.domain.config.DisambiguationConfig
 * @see DisambiguationRule
 */
@Service
public class DisambiguationEngine {

    private static final Logger log = LoggerFactory.getLogger(DisambiguationEngine.class);

    private final Map<TokenType, Function<Party, String>> tokenFieldMap;

    private final SessionRepository sessionRepo;
    private final CustomerPreferenceProvider preferenceProvider;

    public DisambiguationEngine(SessionRepository sessionRepo,
                                 CustomerPreferenceProvider preferenceProvider) {
        this.sessionRepo = sessionRepo;
        this.preferenceProvider = preferenceProvider;
        this.tokenFieldMap = defaultTokenFieldMap();
    }

    private static Map<TokenType, Function<Party, String>> defaultTokenFieldMap() {
        Map<TokenType, Function<Party, String>> map = new LinkedHashMap<>();
        map.put(TokenType.ACCOUNT_NUMBER, Party::getAccountNumber);
        map.put(TokenType.DATE_OF_BIRTH, Party::getDateOfBirth);
        map.put(TokenType.SSN_LAST4, Party::getSsnLast4);
        map.put(TokenType.CARD_LAST4, Party::getCardLast4);
        return Collections.unmodifiableMap(map);
    }

    /**
     * Begins disambiguation for a session that has multiple candidate parties.
     *
     * <p>Applies the configured pre-filter rules, then either resolves to a single party
     * immediately or selects the most discriminating token type to prompt the caller for.
     *
     * @return a response with a prompt and the next token type to collect, or a FAILED
     *         response if rules eliminate all parties or no discriminating token exists
     */
    public AuthenticateResponse start(IvrSession session, DisambiguationConfig config) {
        // 1. Apply configured rules
        List<Party> parties = applyRules(session.getCandidateParties(), config);

        // 2. Check result
        if (parties.isEmpty()) {
            session.setStatus(SessionStatus.FAILED);
            sessionRepo.save(session);
            return buildResponse(session, "No matching parties found after applying disambiguation rules.", null);
        }

        if (parties.size() == 1) {
            log.info("DISAMBIGUATION [{}] resolved to single party={}",
                session.getSessionId(), parties.get(0).getPartyId());
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

    /**
     * Processes a token submission during the disambiguation phase.
     *
     * <p>Matches the submitted value against the remaining candidate parties' corresponding
     * field. On a unique match, transitions the session to
     * {@link com.yourco.ivr.domain.SessionPhase#AUTHENTICATING}. On multiple remaining
     * parties, prompts for the next most-discriminating token.
     *
     * @return a response prompting for more tokens, a FAILED response if the attempt limit
     *         is exceeded, or a resolved response if a single party is identified
     */
    public AuthenticateResponse handleToken(IvrSession session, TokenType tokenType,
                                        String tokenValue, DisambiguationConfig config) {
        // 1. Verify token is usable for disambiguation
        if (!tokenFieldMap.containsKey(tokenType)) {
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
        Function<Party, String> extractor = tokenFieldMap.get(tokenType);

        List<Party> matching = remaining.stream()
            .filter(p -> tokenValue.equals(extractor.apply(p)))
            .collect(Collectors.toList());

        if (matching.isEmpty()) {
            return buildResponse(session,
                "The provided value does not match any known party. Please try again.",
                tokenType);
        }

        if (matching.size() == 1) {
            log.info("DISAMBIGUATION [{}] token match resolved to party={}",
                session.getSessionId(), matching.get(0).getPartyId());
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

    /**
     * Selects the {@link TokenType} that best splits the candidate party list.
     *
     * <p>Uses a greedy min-max-group-size heuristic: for each token type, group parties by
     * their field value and find the largest group; pick the token type whose largest group
     * is smallest. Returns {@code null} if no token type can differentiate the parties.
     */
    TokenType selectDisambiguationToken(List<Party> parties) {
        TokenType bestToken = null;
        int bestMaxGroupSize = Integer.MAX_VALUE;

        for (Map.Entry<TokenType, Function<Party, String>> entry : tokenFieldMap.entrySet()) {
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

    /**
     * Applies each configured {@link DisambiguationRule} in order to the candidate party list.
     * Returns the original list unchanged if no rules are configured.
     */
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

    private AuthenticateResponse resolveParty(IvrSession session, Party party) {
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

    private AuthenticateResponse buildResponse(IvrSession session, String prompt, TokenType nextToken) {
        return AuthenticateResponse.builder()
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
