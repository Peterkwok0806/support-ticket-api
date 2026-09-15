package com.pk.support_ticket_api.users.web;

import com.pk.support_ticket_api.common.domain.enums.Role;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.users.domain.UserStatus;
import com.pk.support_ticket_api.users.dto.CreateUserRequest;
import com.pk.support_ticket_api.users.dto.UpdateUserRequest;
import com.pk.support_ticket_api.users.dto.UserResponse;
import com.pk.support_ticket_api.users.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Users", description = "管理者使用者管理 API")
public class UserAdminController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "取得使用者列表", description = "分頁查詢使用者，支援角色、狀態、關鍵字篩選")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "成功取得使用者列表")
    })
    public ResponseEntity<PageResponse<UserResponse>> findAll(
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResponse<UserResponse> response = userService.findAll(role, status, keyword, page, size);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "取得單一使用者", description = "依 ID 取得使用者詳細資料")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "成功取得使用者"),
            @ApiResponse(responseCode = "404", description = "使用者不存在")
    })
    public ResponseEntity<UserResponse> findById(@PathVariable UUID id) {
        UserResponse response = userService.findById(id);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @Operation(summary = "建立使用者", description = "建立新使用者帳號")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "成功建立使用者"),
            @ApiResponse(responseCode = "400", description = "請求資料驗證失敗"),
            @ApiResponse(responseCode = "409", description = "Email 已被使用")
    })
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse response = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新使用者", description = "更新使用者資料")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "成功更新使用者"),
            @ApiResponse(responseCode = "400", description = "請求資料驗證失敗"),
            @ApiResponse(responseCode = "404", description = "使用者不存在"),
            @ApiResponse(responseCode = "409", description = "Email 已被使用")
    })
    public ResponseEntity<UserResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request
    ) {
        UserResponse response = userService.updateUser(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "停用使用者", description = "停用指定使用者的帳號")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "成功停用使用者"),
            @ApiResponse(responseCode = "400", description = "商業規則限制（不可停用自己、最後一個 Admin）"),
            @ApiResponse(responseCode = "404", description = "使用者不存在")
    })
    public ResponseEntity<UserResponse> deactivate(@PathVariable UUID id) {
        UserResponse response = userService.deactivate(id);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "啟用使用者", description = "啟用指定使用者的帳號")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "成功啟用使用者"),
            @ApiResponse(responseCode = "404", description = "使用者不存在")
    })
    public ResponseEntity<UserResponse> activate(@PathVariable UUID id) {
        UserResponse response = userService.activate(id);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "刪除使用者", description = "軟刪除使用者（設定為 INACTIVE）")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "成功刪除使用者"),
            @ApiResponse(responseCode = "400", description = "商業規則限制（不可刪除自己、最後一個 Admin）"),
            @ApiResponse(responseCode = "404", description = "使用者不存在")
    })
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
