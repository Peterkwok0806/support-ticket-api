package com.pk.support_ticket_api.users.service;

import com.pk.support_ticket_api.common.domain.enums.Role;
import com.pk.support_ticket_api.common.exception.BusinessRuleException;
import com.pk.support_ticket_api.common.exception.ConflictException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.domain.UserSpecification;
import com.pk.support_ticket_api.users.domain.UserStatus;
import com.pk.support_ticket_api.users.dto.CreateUserRequest;
import com.pk.support_ticket_api.users.dto.UpdateUserRequest;
import com.pk.support_ticket_api.users.dto.UserResponse;
import com.pk.support_ticket_api.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Email 已被使用");
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName());
        user.setRole(Role.valueOf(request.role()));
        user.setStatus(UserStatus.ACTIVE);

        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    @Override
    public UserResponse updateUser(UUID userId, UpdateUserRequest request) {
        User user = findUserById(userId);

        if (request.email() != null && !request.email().equals(user.getEmail())) {
            if (userRepository.existsByEmail(request.email())) {
                throw new ConflictException("Email 已被使用");
            }
            user.setEmail(request.email());
        }

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }

        if (request.role() != null) {
            user.setRole(Role.valueOf(request.role()));
        }

        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    @Override
    public UserResponse deactivate(UUID userId) {
        User user = findUserById(userId);

        if (Objects.equals(user.getId(), getCurrentUserId())) {
            throw new BusinessRuleException("不可停用自己的帳號");
        }

        if (isLastAdmin(user)) {
            throw new BusinessRuleException("不可停用最後一個 Admin 帳號");
        }

        user.setStatus(UserStatus.INACTIVE);
        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    @Override
    public UserResponse activate(UUID userId) {
        User user = findUserById(userId);
        user.setStatus(UserStatus.ACTIVE);
        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    @Override
    public void delete(UUID userId) {
        User user = findUserById(userId);

        if (Objects.equals(user.getId(), getCurrentUserId())) {
            throw new BusinessRuleException("不可刪除自己的帳號");
        }

        if (isLastAdmin(user)) {
            throw new BusinessRuleException("不可刪除最後一個 Admin 帳號");
        }

        user.setStatus(UserStatus.INACTIVE);
        userRepository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> findAll(Role role, UserStatus status, String keyword, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> userPage = userRepository.findAll(UserSpecification.withFilters(role, status, keyword), pageRequest);
        return PageResponse.from(userPage, UserResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse findById(UUID userId) {
        User user = findUserById(userId);
        return UserResponse.from(user);
    }

    private User findUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("使用者不存在"));
    }

    private boolean isLastAdmin(User user) {
        if (user.getRole() != Role.ADMIN) {
            return false;
        }
        long activeAdminCount = userRepository.count(
                (root, query, cb) -> cb.and(
                        cb.equal(root.get("role"), Role.ADMIN),
                        cb.equal(root.get("status"), UserStatus.ACTIVE)
                ));
        return activeAdminCount <= 1;
    }

    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CurrentUser currentUser) {
            return currentUser.userId();
        }
        return null;
    }
}
