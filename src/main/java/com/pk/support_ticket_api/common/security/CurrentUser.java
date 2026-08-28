package com.pk.support_ticket_api.common.security;

import java.util.UUID;

public record CurrentUser (UUID userId,
        String email,
        String role){
    
}
