package com.pk.support_ticket_api.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "currentUserAuditorAware")
public class JpaAuditingConfig {
}