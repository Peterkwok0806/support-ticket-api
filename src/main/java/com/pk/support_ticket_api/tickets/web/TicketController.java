package com.pk.support_ticket_api.tickets.web;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.tickets.dto.*;
import com.pk.support_ticket_api.tickets.service.TicketService;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
@Tag(name = "Tickets", description = "工單管理 API")
public class TicketController {

    private final TicketService ticketService;

    @PostMapping
    @Operation(summary = "建立工單", description = "建立新的支援工單")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "成功建立"),
        @ApiResponse(responseCode = "400", description = "請求驗證失敗"),
        @ApiResponse(responseCode = "404", description = "分類不存在")
    })
    public ResponseEntity<TicketResponse> create(
            @Valid @RequestBody CreateTicketRequest request,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        TicketResponse response = ticketService.createTicket(
            request, currentUser.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "取得工單詳情", description = "依 ID 取得工單完整資訊")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功取得"),
        @ApiResponse(responseCode = "404", description = "工單不存在")
    })
    public ResponseEntity<TicketResponse> getById(@PathVariable UUID id) {
        TicketResponse response = ticketService.getTicketById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(summary = "查詢工單列表", description = "分頁查詢工單，支援多重過濾條件")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功取得列表")
    })
    public ResponseEntity<PageResponse<TicketSummaryResponse>> findAll(
            @RequestParam(required = false) List<TicketStatus> statuses,
            @RequestParam(required = false) TicketPriority priority,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID assignedTo,
            @RequestParam(required = false) UUID createdBy,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        TicketFilterRequest filter = new TicketFilterRequest(
            statuses, priority, categoryId, assignedTo, createdBy, keyword);
        PageResponse<TicketSummaryResponse> response = ticketService.getTickets(filter, pageable);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "更新工單", description = "更新工單基本資訊（標題、描述、分類、優先級）")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功更新"),
        @ApiResponse(responseCode = "400", description = "請求驗證失敗"),
        @ApiResponse(responseCode = "404", description = "工單或分類不存在"),
        @ApiResponse(responseCode = "409", description = "工單已關閉，無法更新")
    })
    public ResponseEntity<TicketResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTicketRequest request
    ) {
        TicketResponse response = ticketService.updateTicket(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "變更工單狀態", description = "變更工單狀態（需符合狀態機規則）")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功變更"),
        @ApiResponse(responseCode = "404", description = "工單不存在"),
        @ApiResponse(responseCode = "409", description = "不允許的狀態轉換")
    })
    public ResponseEntity<TicketResponse> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody TicketStatusUpdateRequest request
    ) {
        TicketResponse response = ticketService.updateStatus(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/assign")
    @Operation(summary = "指派工單", description = "指派工單給客服人員（傳入 null 取消指派）")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功指派"),
        @ApiResponse(responseCode = "404", description = "工單或用戶不存在"),
        @ApiResponse(responseCode = "409", description = "工單已關閉，無法指派")
    })
    public ResponseEntity<TicketResponse> assign(
            @PathVariable UUID id,
            @Valid @RequestBody TicketAssignRequest request
    ) {
        TicketResponse response = ticketService.assignTicket(id, request);
        return ResponseEntity.ok(response);
    }
}
