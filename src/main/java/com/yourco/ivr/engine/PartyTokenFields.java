package com.yourco.ivr.engine;

import com.yourco.ivr.domain.Party;
import com.yourco.ivr.domain.TokenType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Shared, immutable mapping from a {@link TokenType} to the accessor for the corresponding
 * {@link Party} field.
 *
 * <p>This is the single source of truth for which PII fields can be matched against a party
 * record. It is used both for party-field verification (identification-only brands, see
 * {@link com.yourco.ivr.engine.validation.ExternalValidator}) and for disambiguation
 * (see {@link com.yourco.ivr.engine.DisambiguationEngine}).
 */
public final class PartyTokenFields {

    /** Token types whose values can be matched against a {@link Party} field, in priority order. */
    public static final Map<TokenType, Function<Party, String>> FIELD_ACCESSORS = buildFieldAccessors();

    private PartyTokenFields() {
    }

    private static Map<TokenType, Function<Party, String>> buildFieldAccessors() {
        Map<TokenType, Function<Party, String>> map = new LinkedHashMap<>();
        map.put(TokenType.ACCOUNT_NUMBER, Party::getAccountNumber);
        map.put(TokenType.DATE_OF_BIRTH, Party::getDateOfBirth);
        map.put(TokenType.SSN_LAST4, Party::getSsnLast4);
        map.put(TokenType.CARD_LAST4, Party::getCardLast4);
        return Collections.unmodifiableMap(map);
    }
}
