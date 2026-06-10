package com.yourco.ivr.engine;

import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.engine.path.ActivePath;
import com.yourco.ivr.engine.path.ActivePathManager;
import com.yourco.ivr.engine.slot.TokenSlotResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.BiFunction;

/**
 * Wires the engine sub-components that require runtime method references.
 */
@Configuration
public class EngineConfig {

    @Bean
    public BiFunction<IvrSession, BrandAuthConfig, ActivePath> activePathResolver(
            ActivePathManager pathManager) {
        return pathManager::resolveActivePath;
    }

    @Bean
    public TokenSlotResolver tokenSlotResolver(
            BiFunction<IvrSession, BrandAuthConfig, ActivePath> activePathResolver) {
        return new TokenSlotResolver(activePathResolver);
    }
}