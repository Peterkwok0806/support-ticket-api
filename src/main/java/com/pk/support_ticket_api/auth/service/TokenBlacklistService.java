package com.pk.support_ticket_api.auth.service;

public interface TokenBlacklistService {

    /**
     * 將 Token 加入黑名單
     * @param jti Token ID (JWT ID)
     * @param ttlSeconds 剩餘有效期（秒）
     */
    void addToBlacklist(String jti, long ttlSeconds);

    /**
     * 檢查 Token 是否在黑名單
     * @param jti Token ID
     * @return 是否在黑名單
     */
    boolean isBlacklisted(String jti);
}
