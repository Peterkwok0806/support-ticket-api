package com.pk.support_ticket_api.categories.web;

import com.pk.support_ticket_api.categories.dto.CategoryResponse;
import com.pk.support_ticket_api.categories.dto.CreateCategoryRequest;
import com.pk.support_ticket_api.categories.dto.UpdateCategoryRequest;
import com.pk.support_ticket_api.categories.service.CategoryService;
import com.pk.support_ticket_api.common.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/categories")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Categories", description = "管理者分類管理 API")
public class CategoryAdminController {

    private final CategoryService categoryService;

    @GetMapping
    @Operation(summary = "取得分類列表", description = "分頁查詢分類，支援啟用狀態、關鍵字篩選")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "成功取得分類列表")
    })
    public ResponseEntity<PageResponse<CategoryResponse>> findAll(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        PageResponse<CategoryResponse> response = categoryService.getAllCategories(active, keyword, pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "取得單一分類", description = "依 ID 取得分類詳細資料")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "成功取得分類"),
            @ApiResponse(responseCode = "404", description = "分類不存在")
    })
    public ResponseEntity<CategoryResponse> findById(@PathVariable UUID id) {
        CategoryResponse response = categoryService.getCategoryById(id);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @Operation(summary = "建立分類", description = "建立新分類")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "成功建立分類"),
            @ApiResponse(responseCode = "400", description = "請求資料驗證失敗"),
            @ApiResponse(responseCode = "409", description = "分類名稱已存在")
    })
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CreateCategoryRequest request) {
        CategoryResponse response = categoryService.createCategory(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新分類", description = "更新分類資料（含 SLA 設定）")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "成功更新分類"),
            @ApiResponse(responseCode = "400", description = "請求資料驗證失敗"),
            @ApiResponse(responseCode = "404", description = "分類不存在"),
            @ApiResponse(responseCode = "409", description = "分類名稱已存在")
    })
    public ResponseEntity<CategoryResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCategoryRequest request
    ) {
        CategoryResponse response = categoryService.updateCategory(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "停用分類", description = "停用指定分類")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "成功停用分類"),
            @ApiResponse(responseCode = "404", description = "分類不存在"),
            @ApiResponse(responseCode = "422", description = "分類已是停用狀態")
    })
    public ResponseEntity<CategoryResponse> deactivate(@PathVariable UUID id) {
        CategoryResponse response = categoryService.deactivateCategory(id);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "啟用分類", description = "啟用指定分類")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "成功啟用分類"),
            @ApiResponse(responseCode = "404", description = "分類不存在"),
            @ApiResponse(responseCode = "422", description = "分類已是啟用狀態")
    })
    public ResponseEntity<CategoryResponse> activate(@PathVariable UUID id) {
        CategoryResponse response = categoryService.activateCategory(id);
        return ResponseEntity.ok(response);
    }
}
