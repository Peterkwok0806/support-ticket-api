package com.pk.support_ticket_api.common.cache;

import java.time.Duration;

/**
 * 快取 TTL（Time To Live）常數定義
 * 定義各類資料的快取存活時間
 */
public final class CacheTtl {

    private CacheTtl() {}

    /** Active Categories：30 分鐘（讀多寫少） */
    public static final Duration ACTIVE_CATEGORIES = Duration.ofMinutes(30);

    /** Ticket Detail：5 分鐘（使用者可能重複查看） */
    public static final Duration TICKET_DETAIL = Duration.ofMinutes(5);

    /** User Profile：10 分鐘（頻繁讀取） */
    public static final Duration USER_PROFILE = Duration.ofMinutes(10);

    /** Dashboard Summary：1 分鐘（統計查詢可接受短暫延遲） */
    public static final Duration DASHBOARD_SUMMARY = Duration.ofMinutes(1);
}
