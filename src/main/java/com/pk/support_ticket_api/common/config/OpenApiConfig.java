package com.pk.support_ticket_api.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customerSupportTicketOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Customer Support Ticket API")
                        .version("v1")
                        .description("Customer Support Ticket System API"));
    }
}