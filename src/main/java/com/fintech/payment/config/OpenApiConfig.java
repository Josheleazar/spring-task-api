package com.fintech.payment.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for SpringDoc. The endpoints themselves are discovered via
 * Swagger's classpath scanning once controllers are added in later phases.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentSettlementOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Payment Settlement API")
                        .version("1.0.0")
                        .description("REST API for accounts, payments, async settlement and audit.")
                        .contact(new Contact().name("Fintech Platform Team").email("platform@fintech.example"))
                        .license(new License().name("Proprietary")));
    }
}
