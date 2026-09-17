package com.pk.support_ticket_api.common.security;

import com.pk.support_ticket_api.common.domain.enums.Role;

import java.util.UUID;

public record CurrentUser (UUID userId,
        String email,
        Role role){

}
