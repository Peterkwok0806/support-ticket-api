package com.pk.support_ticket_api.tickets.web;

import com.pk.support_ticket_api.tickets.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/tickets")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Tickets", description = "管理者工單管理 API")
public class TicketAdminController {

    private final TicketService ticketService;

    @DeleteMapping("/{id}")
    @Operation(summary = "刪除工單", description = "刪除指定工單（管理員專用）")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "成功刪除"),
        @ApiResponse(responseCode = "404", description = "工單不存在")
    })
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ticketService.deleteTicket(id);
        return ResponseEntity.noContent().build();
    }
}
