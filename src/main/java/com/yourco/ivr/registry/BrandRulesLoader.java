package com.yourco.ivr.registry;

import com.yourco.ivr.service.BrandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * Spring startup hook that triggers the initial load of brand configs from disk.
 *
 * <p>Separated from {@link BrandService} so that the service can be unit-tested without
 * triggering filesystem access at construction time. The {@code @PostConstruct} here runs
 * after all beans are wired, at which point the config directory is guaranteed to exist
 * (created by {@link BrandService#init()} which also runs {@code @PostConstruct} — Spring
 * calls {@code @PostConstruct} methods in dependency order, so {@code BrandService} initialises
 * first).
 */
@Component
public class BrandRulesLoader {

    private static final Logger log = LoggerFactory.getLogger(BrandRulesLoader.class);

    private final BrandService brandService;

    public BrandRulesLoader(BrandService brandService) {
        this.brandService = brandService;
    }

    @PostConstruct
    public void loadBrandConfigs() {
        brandService.loadFromDirectory();
        log.info("Brand configs loaded from external directory");
    }
}