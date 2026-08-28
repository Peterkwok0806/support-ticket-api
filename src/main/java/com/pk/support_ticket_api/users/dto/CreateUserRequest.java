package com.pk.support_ticket_api.users.dto;

import com.pk.support_ticket_api.common.domain.enums.Role;
import com.pk.support_ticket_api.common.validation.ValidEnum;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "Email 為必填")
        @Email(message = "無效的 email 格式")
        String email,

        @NotBlank(message = "密碼為必填")
        @Size(min = 8, max = 100, message = "密碼長度需 8-100 字元")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "密碼需包含大小寫字母及數字"
        )
        String password,

        @NotBlank(message = "名稱為必填")
        @Size(min = 2, max = 100, message = "名稱長度需 2-100 字元")
        String displayName,

        @NotNull(message = "角色為必填")
        @ValidEnum(enumClass = Role.class, message = "無效的角色")
        String role
) {}
