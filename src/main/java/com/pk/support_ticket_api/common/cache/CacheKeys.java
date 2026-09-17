package com.pk.support_ticket_api.common.cache;

/**
 * 快取 Key 常數定義
 * 統一管理所有快取 Key，避免 key 命名衝突
 */
public final class CacheKeys {

    private CacheKeys() {}

    // ==================== Category ====================

    /** Active Categories 列表 */
    public static final String CATEGORY_ACTIVE_LIST = "category:active:list";

    // ==================== Ticket ====================

    /**
     * Ticket 詳情
     * @param ticketId Ticket ID
     * @return 快取 Key
     */
    public static String ticket(String ticketId) {
        return "ticket:" + ticketId;
    }

    // ==================== User ====================

    /**
     * User Profile
     * @param userId User ID
     * @return 快取 Key
     */
    public static String user(String userId) {
        return "user:" + userId;
    }

    // ==================== Dashboard ====================

    /** Dashboard 統計摘要 */
    public static final String DASHBOARD_SUMMARY = "dashboard:summary";
}
