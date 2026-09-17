package com.pk.support_ticket_api.common.cache;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 快取 Key 常數與動態 Key 生成
 *
 * ⚠️ 重要：帶分頁/篩選參數的查詢，Key 必須動態包含這些參數！
 *
 * ❌ 錯誤示範（固定 Key）：
 *    "category:active:list"  → 第1頁、第2頁都會命中錯誤的快取
 *
 * ✅ 正確做法（動態 Key）：
 *    "category:active:list:p0:s20:createdAt_desc"  → 不同分頁有不同 Key
 */
public final class CacheKeys {

    private CacheKeys() {}

    // ==================== Category ====================

    /**
     * Active Categories 列表（動態 Key，包含分頁參數）
     * @param pageable 分頁參數
     * @return 快取 Key，格式：category:active:list:p{頁碼}:s{每頁筆數}:{排序}
     */
    public static String activeCategories(Pageable pageable) {
        String sort = pageable.getSort().isSorted()
            ? pageable.getSort().stream()
                .map(o -> o.getProperty() + "_" + o.getDirection().name().toLowerCase())
                .findFirst()
                .orElse("default")
            : "default";
        return String.format("category:active:list:p%d:s%d:%s",
            pageable.getPageNumber(),
            pageable.getPageSize(),
            sort);
    }

    /** Active Categories Pattern（用於失效所有分頁的快取） */
    public static final String ACTIVE_CATEGORIES_PATTERN = "category:active:list:*";

    // ==================== Ticket ====================

    /**
     * Ticket 詳情（固定 Key，ID 已是動態部分）
     * @param ticketId Ticket ID
     * @return 快取 Key
     */
    public static String ticket(String ticketId) {
        return "ticket:" + ticketId;
    }

    // ==================== User ====================

    /**
     * User Profile（固定 Key，ID 已是動態部分）
     * @param userId User ID
     * @return 快取 Key
     */
    public static String user(String userId) {
        return "user:" + userId;
    }

    // ==================== Dashboard ====================

    /** Dashboard 統計摘要（固定 Key，無分頁） */
    public static final String DASHBOARD_SUMMARY = "dashboard:summary";
}
