package com.kilivana.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Customises the OpenAPI document metadata shown by Swagger UI. */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI kilivanaOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Backend system for the Kilivana App Platform")
                .description("Agricultural marketplace and logistics backend: auth, marketplace, "
                        + "orders, payments, logistics, inspections, badges, disputes and admin APIs "
                        + "under the /api/v1 base path.")
                .version("v1"));
    }
}