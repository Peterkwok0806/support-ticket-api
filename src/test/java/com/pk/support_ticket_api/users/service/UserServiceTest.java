package com.pk.support_ticket_api.users.service;

import com.pk.support_ticket_api.common.domain.enums.Role;
import com.pk.support_ticket_api.common.exception.BusinessRuleException;
import com.pk.support_ticket_api.common.exception.ConflictException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.domain.UserStatus;
import com.pk.support_ticket_api.users.dto.CreateUserRequest;
import com.pk.support_ticket_api.users.dto.UpdateUserRequest;
import com.pk.support_ticket_api.users.dto.UserResponse;
import com.pk.support_ticket_api.users.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    @InjectMocks
    private UserServiceImpl userService;

    private UUID targetUserId;
    private UUID currentUserId;
    private User targetUser;

    @BeforeEach
    void setUp() {
        targetUserId = UUID.randomUUID();
        currentUserId = UUID.randomUUID();
        targetUser = createUser(targetUserId, "test@example.com", "Test User", Role.AGENT, UserStatus.ACTIVE);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    class CreateUser {

        @Test
        void createUser_Success() {
            CreateUserRequest request = new CreateUserRequest(
                    "new@example.com", "Password123", "New User", "AGENT"
            );
            when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
            when(passwordEncoder.encode("Password123")).thenReturn("hashedPassword");
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
                ReflectionTestUtils.setField(user, "createdAt", Instant.now());
                ReflectionTestUtils.setField(user, "updatedAt", Instant.now());
                return user;
            });

            UserResponse response = userService.createUser(request);

            assertNotNull(response);
            assertEquals("new@example.com", response.email());
            assertEquals("New User", response.displayName());
            assertEquals(Role.AGENT, response.role());
            assertEquals(UserStatus.ACTIVE, response.status());
            verify(passwordEncoder).encode("Password123");
            verify(userRepository).save(any(User.class));
        }

        @Test
        void createUser_DuplicateEmail() {
            CreateUserRequest request = new CreateUserRequest(
                    "existing@example.com", "Password123", "New User", "AGENT"
            );
            when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

            ConflictException exception = assertThrows(ConflictException.class,
                    () -> userService.createUser(request));

            assertEquals("Email 已被使用", exception.getMessage());
            verify(userRepository, never()).save(any());
        }

        @Test
        void createUser_InvalidRole() {
            CreateUserRequest request = new CreateUserRequest(
                    "new@example.com", "Password123", "New User", "INVALID_ROLE"
            );
            when(userRepository.existsByEmail("new@example.com")).thenReturn(false);

            BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                    () -> userService.createUser(request));

            assertTrue(exception.getMessage().contains("無效的角色"));
            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    class UpdateUser {

        @Test
        void updateUser_Success() {
            UpdateUserRequest request = new UpdateUserRequest("updated@example.com", "Updated Name", null);
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
            when(userRepository.existsByEmail("updated@example.com")).thenReturn(false);
            when(userRepository.save(any(User.class))).thenReturn(targetUser);

            UserResponse response = userService.updateUser(targetUserId, request);

            assertNotNull(response);
            assertEquals("updated@example.com", targetUser.getEmail());
            assertEquals("Updated Name", targetUser.getDisplayName());
            verify(userRepository).existsByEmail("updated@example.com");
            verify(userRepository).save(targetUser);
        }

        @Test
        void updateUser_EmailToExisting() {
            UpdateUserRequest request = new UpdateUserRequest("existing@example.com", null, null);
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
            when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

            ConflictException exception = assertThrows(ConflictException.class,
                    () -> userService.updateUser(targetUserId, request));

            assertEquals("Email 已被使用", exception.getMessage());
            verify(userRepository, never()).save(any());
        }

        @Test
        void updateUser_SameEmail_NoCheck() {
            UpdateUserRequest request = new UpdateUserRequest("test@example.com", "New Name", null);
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
            when(userRepository.save(any(User.class))).thenReturn(targetUser);

            userService.updateUser(targetUserId, request);

            verify(userRepository, never()).existsByEmail(anyString());
            verify(userRepository).save(targetUser);
            assertEquals("New Name", targetUser.getDisplayName());
        }

        @Test
        void updateUser_WithRole() {
            UpdateUserRequest request = new UpdateUserRequest(null, null, "ADMIN");
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
            when(userRepository.save(any(User.class))).thenReturn(targetUser);

            userService.updateUser(targetUserId, request);

            assertEquals(Role.ADMIN, targetUser.getRole());
            verify(userRepository).save(targetUser);
        }
    }

    @Nested
    class Deactivate {

        @BeforeEach
        void setUp() {
            setupSecurityContext(currentUserId);
        }

        @Test
        void deactivate_Success() {
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
            when(userRepository.save(any(User.class))).thenReturn(targetUser);

            UserResponse response = userService.deactivate(targetUserId);

            assertNotNull(response);
            assertEquals(UserStatus.INACTIVE, targetUser.getStatus());
            verify(userRepository).findById(targetUserId);
            verify(userRepository).save(targetUser);
        }

        @Test
        void deactivate_Yourself() {
            ReflectionTestUtils.setField(targetUser, "id", currentUserId);
            when(userRepository.findById(currentUserId)).thenReturn(Optional.of(targetUser));

            BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                    () -> userService.deactivate(currentUserId));

            assertEquals("不可停用自己的帳號", exception.getMessage());
            verify(userRepository, never()).save(any());
        }

        @Test
        void deactivate_LastAdmin() {
            targetUser.setRole(Role.ADMIN);
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
            when(userRepository.count(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(0L);

            BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                    () -> userService.deactivate(targetUserId));

            assertEquals("不可停用最後一個 Admin 帳號", exception.getMessage());
            verify(userRepository, never()).save(any());
        }

        @Test
        void deactivate_NotLastAdmin() {
            targetUser.setRole(Role.ADMIN);
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
            when(userRepository.count(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(1L);
            when(userRepository.save(any(User.class))).thenReturn(targetUser);

            UserResponse response = userService.deactivate(targetUserId);

            assertNotNull(response);
            assertEquals(UserStatus.INACTIVE, targetUser.getStatus());
            verify(userRepository).save(targetUser);
        }

        @Test
        void deactivate_UnauthenticatedUser() {
            SecurityContextHolder.clearContext();
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));

            BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                    () -> userService.deactivate(targetUserId));

            assertEquals("無法確認操作者身份", exception.getMessage());
        }
    }

    @Nested
    class Activate {

        @Test
        void activate_Success() {
            targetUser.setStatus(UserStatus.INACTIVE);
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
            when(userRepository.save(any(User.class))).thenReturn(targetUser);

            UserResponse response = userService.activate(targetUserId);

            assertNotNull(response);
            assertEquals(UserStatus.ACTIVE, targetUser.getStatus());
            verify(userRepository).findById(targetUserId);
            verify(userRepository).save(targetUser);
        }
    }

    @Nested
    class Delete {

        @BeforeEach
        void setUp() {
            setupSecurityContext(currentUserId);
        }

        @Test
        void delete_Success() {
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
            when(userRepository.save(any(User.class))).thenReturn(targetUser);

            userService.delete(targetUserId);

            assertEquals(UserStatus.INACTIVE, targetUser.getStatus());
            verify(userRepository).findById(targetUserId);
            verify(userRepository).save(targetUser);
        }

        @Test
        void delete_Yourself() {
            ReflectionTestUtils.setField(targetUser, "id", currentUserId);
            when(userRepository.findById(currentUserId)).thenReturn(Optional.of(targetUser));

            BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                    () -> userService.delete(currentUserId));

            assertEquals("不可刪除自己的帳號", exception.getMessage());
            verify(userRepository, never()).save(any());
        }

        @Test
        void delete_LastAdmin() {
            targetUser.setRole(Role.ADMIN);
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
            when(userRepository.count(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(0L);

            BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                    () -> userService.delete(targetUserId));

            assertEquals("不可刪除最後一個 Admin 帳號", exception.getMessage());
            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    class FindAll {

        @Test
        void findAll_WithFilters() {
            PageRequest pageRequest = PageRequest.of(0, 20);
            Page<User> userPage = new PageImpl<>(List.of(targetUser), pageRequest, 1);
            when(userRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class))).thenReturn(userPage);

            PageResponse<UserResponse> response = userService.findAll(Role.AGENT, UserStatus.ACTIVE, "test", 0, 20);

            assertNotNull(response);
            assertEquals(1, response.content().size());
            assertEquals(0, response.page());
            assertEquals(1, response.size());
            assertEquals(1, response.totalElements());
            verify(userRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class));
        }

        @Test
        void findAll_NoFilters() {
            PageRequest pageRequest = PageRequest.of(0, 20);
            Page<User> userPage = new PageImpl<>(List.of(targetUser), pageRequest, 1);
            when(userRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class))).thenReturn(userPage);

            PageResponse<UserResponse> response = userService.findAll(null, null, null, 0, 20);

            assertNotNull(response);
            assertEquals(1, response.content().size());
            verify(userRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class));
        }
    }

    @Nested
    class FindById {

        @Test
        void findById_Success() {
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));

            UserResponse response = userService.findById(targetUserId);

            assertNotNull(response);
            assertEquals(targetUserId.toString(), response.id());
            verify(userRepository).findById(targetUserId);
        }

        @Test
        void findById_NotFound() {
            UUID nonExistentId = UUID.randomUUID();
            when(userRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                    () -> userService.findById(nonExistentId));

            assertEquals("使用者不存在", exception.getMessage());
        }
    }

    private User createUser(UUID id, String email, String displayName, Role role, UserStatus status) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail(email);
        user.setPasswordHash("hashedPassword");
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setStatus(status);
        ReflectionTestUtils.setField(user, "createdAt", Instant.now());
        ReflectionTestUtils.setField(user, "updatedAt", Instant.now());
        return user;
    }

    private void setupSecurityContext(UUID userId) {
        CurrentUser currentUser = new CurrentUser(userId, "admin@example.com", "ADMIN");
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                currentUser, null, List.of()
        );
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
    }
}
