package com.yourco.ivr;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ivrAuthEngineOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("IVR Token Authentication Engine API")
                .description("Multi-brand IVR Token Authentication Engine with progressive auth levels, " +
                    "token sharing policies, and rule-driven path fallbacks.")
                .version("1.1.0")
                .contact(new Contact()
                    .name("Engineering Team")
                    .email("eng@yourco.com"))
                .license(new License()
                    .name("Proprietary")
                    .url("https://yourco.com")));
    }
}