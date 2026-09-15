package com.pk.support_ticket_api.categories.web;

import com.pk.support_ticket_api.categories.dto.CategorySummaryResponse;
import com.pk.support_ticket_api.categories.service.CategoryService;
import com.pk.support_ticket_api.common.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/categories")
@RequiredArgsConstructor
@Tag(name = "Categories", description = "分類公開 API")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @Operation(summary = "取得啟用中的分類", description = "取得所有已啟用的分類（公開端點）")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "成功取得分類列表")
    })
    public ResponseEntity<PageResponse<CategorySummaryResponse>> getActiveCategories(
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
            Pageable pageable
    ) {
        PageResponse<CategorySummaryResponse> response = categoryService.getActiveCategories(pageable);
        return ResponseEntity.ok(response);
    }
}
