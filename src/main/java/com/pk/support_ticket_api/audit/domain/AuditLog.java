package com.pk.support_ticket_api.audit.domain;

import com.pk.support_ticket_api.common.domain.BaseEntity;
import com.pk.support_ticket_api.common.domain.enums.AuditAction;
import jakarta.persistence.*;
import lombok.Getter;

import java.util.UUID;

/**
 * Audit Log 實體。
 *
 * <p>強制使用工廠方法創建实例，禁止直接使用建構式。</p>
 *
 * <ul>
 *   <li>{@link #create(UUID, UUID, AuditAction)} - 基本建立</li>
 *   <li>{@link #createFieldChange(UUID, UUID, AuditAction, AuditFieldName, String, String)} - 欄位變更</li>
 *   <li>{@link #createCommentAdded(UUID, UUID, boolean)} - Comment 新增</li>
 * </ul>
 */
@Getter
@Entity
@Table(name = "audit_logs")
public class AuditLog extends BaseEntity {

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_name", length = 30)
    private AuditFieldName fieldName;

    @Column(name = "old_value", length = 500)
    private String oldValue;

    @Column(name = "new_value", length = 500)
    private String newValue;

    @Column(name = "internal")
    private Boolean internal;

    // ==================== 建構式（供 JPA 使用） ====================

    /**
     * 無參建構式，供 JPA 使用。
     */
    protected AuditLog() {
        // JPA 需要無參建構式
    }

    // ==================== 工廠方法 ====================

    /**
     * 建立基本 AuditLog。
     *
     * @param actorId 執行操作的使用者 ID
     * @param ticketId 操作的 Ticket ID
     * @param action 操作類型
     * @return AuditLog 實例
     */
    public static AuditLog create(UUID actorId, UUID ticketId, AuditAction action) {
        AuditLog log = new AuditLog();
        log.actorId = actorId;
        log.ticketId = ticketId;
        log.action = action;
        return log;
    }

    /**
     * 建立欄位變更 AuditLog。
     *
     * @param actorId 執行操作的使用者 ID
     * @param ticketId 操作的 Ticket ID
     * @param action 操作類型
     * @param fieldName 變更的欄位名稱
     * @param oldValue 舊值
     * @param newValue 新值
     * @return AuditLog 實例
     */
    public static AuditLog createFieldChange(
            UUID actorId,
            UUID ticketId,
            AuditAction action,
            AuditFieldName fieldName,
            String oldValue,
            String newValue
    ) {
        AuditLog log = create(actorId, ticketId, action);
        log.fieldName = fieldName;
        log.oldValue = oldValue;
        log.newValue = newValue;
        return log;
    }

    /**
     * 建立 Comment 新增 AuditLog。
     *
     * @param actorId 執行操作的使用者 ID
     * @param ticketId 操作的 Ticket ID
     * @param internal 是否為內部留言
     * @return AuditLog 實例
     */
    public static AuditLog createCommentAdded(
            UUID actorId,
            UUID ticketId,
            boolean internal
    ) {
        AuditLog log = create(actorId, ticketId, AuditAction.COMMENT_ADDED);
        log.internal = internal;
        return log;
    }
}
