package com.yourco.ivr.lookup;

import com.yourco.ivr.domain.TokenType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * In-code {@link VerificationBindings}. This is the single place to wire a token to a backend
 * {@link TokenLookupService}.
 *
 * <p>The map is <strong>empty by default</strong>, so every token is format-checked only until a
 * binding is added here. The default impl is brand-agnostic — it ignores {@code brandId} — but the
 * interface carries it so brand-specific bindings can be added later without an API change.
 *
 * <p>To enable backend verification for a token, add an entry in {@link #bindings()}, e.g.:
 * <pre>{@code
 * m.put(TokenType.SSN, new VerificationBinding("experian-ssn", null, true));
 * }</pre>
 */
@Component
public class DefaultVerificationBindings implements VerificationBindings {

    private final Map<TokenType, VerificationBinding> bindings = bindings();

    /** Edit this method to bind tokens to lookup services in code. */
    private static Map<TokenType, VerificationBinding> bindings() {
        Map<TokenType, VerificationBinding> m = new EnumMap<>(TokenType.class);
        // No bindings by default — add entries here to enable backend verification.
        return m;
    }

    @Override
    public VerificationBinding bindingFor(String brandId, TokenType tokenType) {
        return bindings.get(tokenType);
    }
}
