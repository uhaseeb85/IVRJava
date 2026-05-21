package com.yourco.ivr.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.service.BrandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

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