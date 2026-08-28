package com.pk.support_ticket_api.common.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component("currentUserAuditorAware")
public class CurrentUserAuditorAware implements AuditorAware<UUID>{
    @Override
    public Optional<UUID> getCurrentAuditor() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return Optional.empty();
        }

        Object principal = authentication.getPrincipal();

        if (principal instanceof CurrentUser currentUser) {
            return Optional.of(currentUser.userId());
        }

        return Optional.empty();
    }
}