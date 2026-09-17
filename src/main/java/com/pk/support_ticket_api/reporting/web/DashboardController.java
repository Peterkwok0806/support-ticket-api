package com.pk.support_ticket_api.reporting.web;

import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.reporting.dto.DashboardSummaryResponse;
import com.pk.support_ticket_api.reporting.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "儀表板統計 API")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    @Operation(summary = "取得 Dashboard 統計摘要")
    public ResponseEntity<DashboardSummaryResponse> getSummary(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        DashboardSummaryResponse response = dashboardService.getSummary(currentUser.userId());
        return ResponseEntity.ok(response);
    }
}
