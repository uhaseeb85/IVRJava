package com.yourco.ivr.registry;

import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.exception.UnknownBrandException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the atomic-swap reload contract of {@link BrandRulesRegistry#replaceAll(Map)}:
 * the whole brand set is replaced in one shot, and the registry keeps its own copy of the
 * supplied map (so later mutation of the source cannot corrupt it).
 */
class BrandRulesRegistryTest {

    private static BrandAuthConfig cfg(String id) {
        BrandAuthConfig c = new BrandAuthConfig();
        c.setBrandId(id);
        return c;
    }

    @Test
    void replaceAllSwapsTheEntireBrandSet() {
        BrandRulesRegistry registry = new BrandRulesRegistry();
        registry.register(cfg("legacy"));

        Map<String, BrandAuthConfig> reloaded = new HashMap<>();
        reloaded.put("acme", cfg("acme"));
        reloaded.put("globex", cfg("globex"));
        registry.replaceAll(reloaded);

        assertTrue(registry.contains("acme"));
        assertTrue(registry.contains("globex"));
        assertFalse(registry.contains("legacy"));
        assertThrows(UnknownBrandException.class, () -> registry.get("legacy"));
    }

    @Test
    void replaceAllTakesADefensiveCopyOfTheSource() {
        BrandRulesRegistry registry = new BrandRulesRegistry();

        Map<String, BrandAuthConfig> reloaded = new HashMap<>();
        reloaded.put("acme", cfg("acme"));
        registry.replaceAll(reloaded);

        // Mutating the source map after the swap must not affect the registry.
        reloaded.clear();

        assertTrue(registry.contains("acme"));
    }
}
