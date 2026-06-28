package com.yourco.ivr.lookup;

import com.yourco.ivr.exception.UnknownLookupServiceException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory registry of all {@link TokenLookupService} beans, built by Spring at startup.
 *
 * <p>Adding a backend integration is just adding a new {@code @Component implements
 * TokenLookupService}; it is auto-wired into this registry with no further configuration.
 * Mirrors the pattern of {@link com.yourco.ivr.validator.TokenValidatorRegistry}.
 */
@Component
public class LookupServiceRegistry {

    private final Map<String, TokenLookupService> byId = new LinkedHashMap<>();

    public LookupServiceRegistry(List<TokenLookupService> services) {
        for (TokenLookupService svc : services) {
            String id = svc.id();
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalStateException(
                    "TokenLookupService " + svc.getClass().getName() + " has a null/blank id()");
            }
            TokenLookupService existing = byId.putIfAbsent(id, svc);
            if (existing != null) {
                throw new IllegalStateException("Duplicate lookup service id '" + id + "' from "
                    + existing.getClass().getName() + " and " + svc.getClass().getName());
            }
        }
    }

    /** @throws UnknownLookupServiceException if no service is registered under {@code id}. */
    public TokenLookupService get(String id) {
        TokenLookupService svc = byId.get(id);
        if (svc == null) {
            throw new UnknownLookupServiceException(id);
        }
        return svc;
    }

    /** Returns all registered lookup services. */
    public Collection<TokenLookupService> all() {
        return byId.values();
    }

    public boolean contains(String id) {
        return byId.containsKey(id);
    }
}
