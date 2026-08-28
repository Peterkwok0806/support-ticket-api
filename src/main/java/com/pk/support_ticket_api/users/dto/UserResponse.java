package com.pk.support_ticket_api.users.dto;

import com.pk.support_ticket_api.common.domain.enums.Role;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.domain.UserStatus;

import java.time.Instant;

public record UserResponse(
        String id,
        String email,
        String displayName,
        Role role,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId().toString(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
