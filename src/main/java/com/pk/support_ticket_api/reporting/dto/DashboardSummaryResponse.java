package com.pk.support_ticket_api.reporting.dto;

import java.util.Map;

/**
 * Dashboard 統計摘要回應
 */
public record DashboardSummaryResponse(
    long openCount,               // Open 狀態工單數
    long inProgressCount,         // In Progress 狀態工單數
    long overdueCount,            // 逾期工單數
    long resolvedTodayCount,      // 今日已解決工單數
    Map<String, Long> byPriority, // 按優先級分組（key 為 Priority 名稱）
    Map<String, Long> byCategory  // 按分類分組（key 為 Category 名稱）
) {}
