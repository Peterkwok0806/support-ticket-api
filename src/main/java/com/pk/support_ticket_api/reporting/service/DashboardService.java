package com.pk.support_ticket_api.reporting.service;

import com.pk.support_ticket_api.reporting.dto.DashboardSummaryResponse;

import java.util.UUID;

/**
 * Dashboard 統計服務介面
 */
public interface DashboardService {

    /**
     * 取得 Dashboard 統計摘要
     * 結果會被快取，TTL 為 1 分鐘
     *
     * @param currentUserId 目前登入使用者的 ID（用於權限控制）
     * @return Dashboard 統計摘要
     */
    DashboardSummaryResponse getSummary(UUID currentUserId);
}
