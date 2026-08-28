package com.pk.support_ticket_api.users.dto;

import com.pk.support_ticket_api.common.domain.enums.Role;
import com.pk.support_ticket_api.common.validation.ValidEnum;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Email(message = "無效的 email 格式")
        String email,

        @Size(min = 2, max = 100, message = "名稱長度需 2-100 字元")
        String displayName,

        @ValidEnum(enumClass = Role.class, message = "無效的角色")
        String role
) {}
