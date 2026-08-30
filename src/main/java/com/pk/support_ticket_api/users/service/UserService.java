package com.pk.support_ticket_api.users.service;

import com.pk.support_ticket_api.common.domain.enums.Role;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.users.domain.UserStatus;
import com.pk.support_ticket_api.users.dto.CreateUserRequest;
import com.pk.support_ticket_api.users.dto.UpdateUserRequest;
import com.pk.support_ticket_api.users.dto.UserResponse;

import java.util.UUID;

public interface UserService {

    UserResponse createUser(CreateUserRequest request);

    UserResponse updateUser(UUID userId, UpdateUserRequest request);

    UserResponse deactivate(UUID userId);

    UserResponse activate(UUID userId);

    void delete(UUID userId);

    PageResponse<UserResponse> findAll(Role role, UserStatus status, String keyword, int page, int size);

    UserResponse findById(UUID userId);
}
