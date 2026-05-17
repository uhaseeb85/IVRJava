package com.yourco.ivr.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;

@Component
public class BrandRulesLoader {

    private static final Logger log = LoggerFactory.getLogger(BrandRulesLoader.class);

    private final BrandRulesRegistry registry;
    private final ObjectMapper mapper;

    public BrandRulesLoader(BrandRulesRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @PostConstruct
    public void loadBrandConfigs() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:brands/*.json");

            for (Resource resource : resources) {
                try {
                    BrandAuthConfig config = mapper.readValue(
                        resource.getInputStream(),
                        BrandAuthConfig.class
                    );
                    registry.register(config);
                    log.info("Loaded brand config: {} from {}", config.getBrandId(), resource.getFilename());
                } catch (Exception e) {
                    log.error("Failed to load brand config from {}", resource.getFilename(), e);
                }
            }

            log.info("Loaded {} brand configuration(s)", resources.length);
        } catch (Exception e) {
            log.error("Failed to scan for brand config files", e);
        }
    }
}