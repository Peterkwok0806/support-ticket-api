package com.pk.support_ticket_api.comments.web;

import com.pk.support_ticket_api.comments.dto.CommentResponse;
import com.pk.support_ticket_api.comments.dto.CreateCommentRequest;
import com.pk.support_ticket_api.comments.service.CommentService;
import com.pk.support_ticket_api.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/tickets/{ticketId}/comments")
@RequiredArgsConstructor
@Tag(name = "Comments", description = "工單留言 API")
public class CommentController {

    private final CommentService commentService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "新增留言", description = "對指定工單新增留言（公開回覆或內部討論）")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "成功建立"),
        @ApiResponse(responseCode = "400", description = "請求驗證失敗", ref = "BadRequest"),
        @ApiResponse(responseCode = "403", description = "無權限留言或 Customer 嘗試建立內部留言", ref = "Forbidden"),
        @ApiResponse(responseCode = "404", description = "工單不存在", ref = "NotFound")
    })
    public ResponseEntity<CommentResponse> create(
            @PathVariable UUID ticketId,
            @Valid @RequestBody CreateCommentRequest request,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        CommentResponse response = commentService.createComment(
            ticketId, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "查詢留言列表", description = "取得指定工單的所有留言（依角色過濾 internal note）")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功取得列表"),
        @ApiResponse(responseCode = "403", description = "無權限存取此工單", ref = "Forbidden"),
        @ApiResponse(responseCode = "404", description = "工單不存在", ref = "NotFound")
    })
    public ResponseEntity<List<CommentResponse>> findAll(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        List<CommentResponse> response = commentService.getCommentsByTicketId(
            ticketId, currentUser);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{commentId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "查詢單一留言", description = "取得指定工單的單一留言")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功取得"),
        @ApiResponse(responseCode = "403", description = "無權限讀取內部留言", ref = "Forbidden"),
        @ApiResponse(responseCode = "404", description = "工單或留言不存在", ref = "NotFound")
    })
    public ResponseEntity<CommentResponse> getById(
            @PathVariable UUID ticketId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        CommentResponse response = commentService.getCommentById(
            ticketId, commentId, currentUser);
        return ResponseEntity.ok(response);
    }
}
