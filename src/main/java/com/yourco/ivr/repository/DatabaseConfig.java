package com.yourco.ivr.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;

@Configuration
public class DatabaseConfig {

    private final DataSource dataSource;

    public DatabaseConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void initializeDatabase() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema.sql"));
        populator.setContinueOnError(true);
        populator.execute(dataSource);
    }
}