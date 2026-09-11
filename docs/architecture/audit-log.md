# Audit Log 模組架構說明

## 1. 架構目標與限制

### 1.1 設計目標

| 目標 | 說明 |
|------|------|
| 領域驅動設計 | 以 Domain 為核心，確保商業邏輯內聚於 Audit Log 模組 |
| 分層架構 | 嚴格遵守 Controller → Service → Repository 分層 |
| 權限控制 | Audit Log 可見範圍與 Ticket 存取權限一致 |
| 事務一致性 | Audit Log 寫入與業務操作在同一 Transaction 內完成 |
| 可測試性 | 所有商業邏輯可透過單元測試驗證 |
| 一致性錯誤處理 | 統一使用既有 Exception 體系 |
| 永久保存 | Audit Log 不可編輯、不可刪除，確保稽核完整性 |

### 1.2 約束條件

| 約束 | 說明 |
|------|------|
| 依賴方向 | 外部依賴（如 Repository、External Service）注入 Service，不可直接引入 Domain |
| 事務邊界 | Service 層為事務邊界，Audit Log 寫入與業務操作在同一 Transaction |
| 唯讀優化 | 查詢操作標記 `@Transactional(readOnly = true)` |
| 封裝性 | Entity 欄位僅透過 Getter/Setter 存取，不暴露內部實作 |
| 不可變性 | Audit Log 建立後不可修改、不可刪除 |
| 寫入失敗策略 | Audit Log 寫入失敗時，業務操作也失敗（同一 Transaction） |

---

## 2. 模組邊界與責任

### 2.1 模組結構

```
audit/
├── domain/           # 領域模型（核心）
├── dto/              # 資料傳輸物件
├── repository/       # 資料存取
├── service/          # 商業邏輯
└── web/              # API 端點
```

### 2.2 責任歸屬

| 層 | 類別 | 責任 |
|----|------|------|
| **domain** | `AuditLog` | 純領域實體，僅承載狀態與資料，不含商業邏輯 |
| **domain** | `AuditAction` | 操作類型枚舉 |
| **domain** | `AuditFieldName` | 欄位名稱枚舉 |
| **dto** | Response DTOs | 格式化輸出，隔離 Entity 與 API 契約 |
| **repository** | `AuditLogRepository` | 資料庫存取，僅暴露查詢方法 |
| **service** | `AuditLogService` | 商業邏輯編排；**執行權限驗證** |
| **web** | `TicketAuditLogController` | HTTP 協定處理，請求轉發 |

### 2.3 不應出現的職責

| 位置 | 不應包含 |
|------|----------|
| Controller | 商業邏輯、資料庫查詢、權限驗證 |
| Repository | 商業邏輯、業務規則 |
| Entity | 跨領域邏輯、HTTP 處理 |
| DTO | 商業邏輯、資料庫存取 |
| AuditLogService | 直接寫入 Audit Log（由業務 Service 負責） |

### 2.4 寫入職責歸屬

Audit Log 的寫入職責分散在業務 Service 中，而非集中在 AuditLogService：

| 寫入時機 | 負責 Service | 說明 |
|----------|-------------|------|
| Ticket 建立 | `TicketServiceImpl` | 在 `createTicket()` 中記錄 |
| 狀態變更 | `TicketServiceImpl` | 在 `updateStatus()` 中記錄 |
| Priority 變更 | `TicketServiceImpl` | 在 `updateTicket()` 中記錄 |
| Agent 指派 | `TicketServiceImpl` | 在 `assignTicket()` 中記錄 |
| Comment 新增 | `CommentServiceImpl` | 在 `createComment()` 中記錄 |

**設計理由**：
- Audit Log 是業務操作的附屬產物，寫入時機與業務邏輯密合
- 集中在 AuditLogService 會造成雙重事務管理問題
- 分散寫入確保 Audit Log 與業務操作原子性一致

---

## 3. 依賴方向

### 3.1 依賴關係圖

```
┌─────────────────────────────────────────────────────────┐
│                      web/                                │
│           (TicketAuditLogController)                     │
│                                                          │
│              depends on: AuditLogService                 │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│                      service/                            │
│              (AuditLogServiceImpl)                        │
│                                                          │
│  depends on: AuditLog, AuditLogRepository,               │
│              TicketRepository, UserRepository             │
└──────────────────────┬──────────────────────────────────┘
                       │
        ┌──────────────┴──────────────┐
        ▼                              ▼
┌───────────────────┐      ┌───────────────────┐
│     domain/       │      │    repository/    │
│    AuditLog       │      │ AuditLogRepository │
└───────────────────┘      └───────────────────┘
```

### 3.2 依賴規則

```
web ──► service ──► domain + repository
               └──► dto（資料格式轉換）
```

- **Domain 不依賴任何外部模組**（最高內聚）
- **Service 可依賴 Domain、Repository、其他 Service**
- **Repository 僅依賴 JPA/Spring Data**
- **DTO 為啞物件，僅用於資料傳遞**

### 3.3 跨模組依賴

```
audit.service ──► tickets.repository（驗證 Ticket 存在）
audit.service ──► users.repository（取得操作者名稱）
```

### 3.4 業務 Service 對 AuditLog 的依賴

```
tickets.service ──► audit.repository
comments.service ──► audit.repository
```

---

## 4. 核心資料模型的責任歸屬

### 4.1 AuditLog Entity

```java
@Entity
@Table(name = "audit_logs")
public class AuditLog extends BaseEntity {

    // 誰做了這個操作
    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    // 發生在哪張 Ticket
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    // 操作類型
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private AuditAction action;

    // 變更的欄位名稱（用於 INDEXED 欄位）
    @Enumerated(EnumType.STRING)
    @Column(name = "field_name", length = 30)
    private AuditFieldName fieldName;

    // 變更前的值
    @Column(name = "old_value", length = 500)
    private String oldValue;

    // 變更後的值
    @Column(name = "new_value", length = 500)
    private String newValue;

    // 是否為內部操作（用於 COMMENT_ADDED）
    @Column(name = "internal")
    private Boolean internal;
}
```

**責任**：
- 承載 Audit Log 的所有屬性
- 提供資料讀寫接口
- 由 JPA 管理生命週期

**不含**：
- 權限驗證邏輯
- 工廠方法邏輯（工廠方法在工廠靜態方法中）
- 跨領域操作

**設計決策**：
- 繼承 `BaseEntity`（非 `VersionedEntity`），因為 Audit Log 不可編輯
- `oldValue` / `newValue` 使用 `VARCHAR(500)` 而非 `TEXT`，足以儲存常見值

### 4.2 AuditAction 枚舉

```java
public enum AuditAction {
    TICKET_CREATED,      // Ticket 建立
    STATUS_CHANGED,       // 狀態變更
    PRIORITY_CHANGED,     // Priority 變更
    ASSIGNED,             // 指派 Agent
    UNASSIGNED,           // 取消指派
    COMMENT_ADDED,        // Comment 新增
    ATTACHMENT_ADDED,    // 預留（尚未實作）
    RESOLVED,            // Ticket 解決（狀態變更為 RESOLVED）
    CLOSED               // Ticket 關閉（狀態變更為 CLOSED）
}
```

### 4.3 AuditFieldName 枚舉

```java
public enum AuditFieldName {
    STATUS,     // 狀態
    PRIORITY,   // 優先級
    ASSIGNEE    // 指派對象
}
```

### 4.4 AuditLog 工廠方法

```java
public class AuditLog {

    // 基礎建立（無值變更）
    public static AuditLog create(UUID actorId, UUID ticketId, AuditAction action) {
        AuditLog log = new AuditLog();
        log.setActorId(actorId);
        log.setTicketId(ticketId);
        log.setAction(action);
        return log;
    }

    // 欄位變更（記錄 oldValue/newValue）
    public static AuditLog createFieldChange(
            UUID actorId,
            UUID ticketId,
            AuditAction action,
            AuditFieldName fieldName,
            String oldValue,
            String newValue
    ) {
        AuditLog log = create(actorId, ticketId, action);
        log.setFieldName(fieldName);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        return log;
    }

    // Comment 新增（記錄 internal 標記）
    public static AuditLog createCommentAdded(
            UUID actorId,
            UUID ticketId,
            boolean internal
    ) {
        AuditLog log = create(actorId, ticketId, AuditAction.COMMENT_ADDED);
        log.setInternal(internal);
        return log;
    }
}
```

---

## 5. 主要資料流程

### 5.1 查詢 Audit Log（Customer）

```
Client GET /api/v1/tickets/{ticketId}/audit-logs
         JWT: CUSTOMER
         │
         ▼
┌─────────────────────────────────────┐
│  @PreAuthorize 角色驗證             │
│  TicketAuditLogController.findAll() │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     AuditLogServiceImpl             │
│  1. validate ticket exists         │
│  2. validate customer owns ticket  │ ◄── 驗證 createdBy = currentUser
│  3. fetch audit logs              │
│  4. enrich actorName              │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     AuditLogRepository              │
│  SELECT * FROM audit_logs          │
│    WHERE ticket_id = ?             │
│    ORDER BY created_at DESC        │
└─────────────────────────────────────┘
```

### 5.2 查詢 Audit Log（Agent）

```
Client GET /api/v1/tickets/{ticketId}/audit-logs
         JWT: AGENT
         │
         ▼
┌─────────────────────────────────────┐
│  @PreAuthorize 角色驗證             │
│  TicketAuditLogController.findAll() │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     AuditLogServiceImpl             │
│  1. validate ticket exists         │
│  2. validate agent is assigned    │ ◄── 驗證 assignedTo = currentUser
│  3. fetch audit logs              │
│  4. enrich actorName              │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     AuditLogRepository              │
│  SELECT * FROM audit_logs          │
│    WHERE ticket_id = ?             │
│    ORDER BY created_at DESC        │
└─────────────────────────────────────┘
```

### 5.3 查詢 Audit Log（Admin）

```
Client GET /api/v1/tickets/{ticketId}/audit-logs
         JWT: ADMIN
         │
         ▼
┌─────────────────────────────────────┐
│  @PreAuthorize 角色驗證             │
│  TicketAuditLogController.findAll() │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     AuditLogServiceImpl             │
│  1. validate ticket exists         │
│  2. admin bypass all restrictions  │ ◄── ADMIN 可查詢所有 Ticket
│  3. fetch audit logs              │
│  4. enrich actorName              │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     AuditLogRepository              │
│  SELECT * FROM audit_logs          │
│    WHERE ticket_id = ?             │
│    ORDER BY created_at DESC        │
└─────────────────────────────────────┘
```

### 5.4 寫入 Audit Log（Ticket 建立）

```
TicketServiceImpl.createTicket()
         │
         ▼
┌─────────────────────────────────────┐
│     TicketServiceImpl               │
│  1. validate category exists        │
│  2. calculate sla deadline         │
│  3. create Ticket                  │
│  4. save Ticket                   │
│  5. save AuditLog ← TICKET_CREATED│ ◄── 同一 Transaction
│  6. return response               │
└─────────────────────────────────────┘
```

### 5.5 寫入 Audit Log（狀態變更）

```
TicketServiceImpl.updateStatus()
         │
         ▼
┌─────────────────────────────────────┐
│     TicketServiceImpl               │
│  1. find ticket by id              │
│  2. validate permission           │
│  3. validate state transition      │
│  4. save old status               │
│  5. set new status                │
│  6. handle side effects           │
│  7. save AuditLog ← STATUS_CHANGED│ ◄── 同一 Transaction
│     (oldValue → newValue)          │
│  8. save Ticket                   │
│  9. return response               │
└─────────────────────────────────────┘
```

### 5.6 寫入 Audit Log（Comment 新增）

```
CommentServiceImpl.createComment()
         │
         ▼
┌─────────────────────────────────────┐
│     CommentServiceImpl              │
│  1. validate ticket exists         │
│  2. validate access permission     │
│  3. validate internal permission    │
│  4. create Comment                 │
│  5. save Comment                   │
│  6. save AuditLog ← COMMENT_ADDED │ ◄── 同一 Transaction
│     (internal: true/false)         │
│  7. return response               │
└─────────────────────────────────────┘
```

---

## 6. 介面設計原則

### 6.1 Service 介面（契約）

```java
public interface AuditLogService {

    // 查詢 Ticket 的 Audit Log 列表（依 currentUser 執行權限驗證）
    PageResponse<AuditLogResponse> getAuditLogsByTicketId(
        UUID ticketId,
        Pageable pageable,
        CurrentUser currentUser
    );
}
```

**設計原則**：
- 回傳 `PageResponse<AuditLogResponse>`，支援分頁
- 使用 `UUID` 作為 ID 型別
- 方法傳入 `CurrentUser` 用於權限驗證
- 不提供寫入方法（寫入由業務 Service 負責）
- 不提供刪除方法（永久保存）

### 6.2 Controller 權限標註

```java
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Audit Logs", description = "稽核日誌 API")
public class TicketAuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PageResponse<AuditLogResponse>> findAll(
            @PathVariable UUID ticketId,
            Pageable pageable,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        PageResponse<AuditLogResponse> response =
            auditLogService.getAuditLogsByTicketId(ticketId, pageable, currentUser);
        return ResponseEntity.ok(response);
    }
}
```

**設計原則**：
- `@PreAuthorize("isAuthenticated()")` 僅驗證是否登入
- 詳細權限邏輯由 Service 層執行（因為涉及 Ticket 存取權限）

### 6.3 Response DTO 格式

```java
public record AuditLogResponse(
    UUID id,
    UUID actorId,
    String actorName,         // 展示用名稱
    UUID ticketId,
    AuditAction action,
    AuditFieldName fieldName,
    String oldValue,
    String newValue,
    Boolean internal,
    Instant createdAt
) {

    public static AuditLogResponse from(AuditLog auditLog) {
        return new AuditLogResponse(
            auditLog.getId(),
            auditLog.getActorId(),
            null,  // actorName 由 Service 層填充
            auditLog.getTicketId(),
            auditLog.getAction(),
            auditLog.getFieldName(),
            auditLog.getOldValue(),
            auditLog.getNewValue(),
            auditLog.getInternal(),
            auditLog.getCreatedAt()
        );
    }
}
```

---

## 7. 錯誤處理原則

### 7.1 例外使用策略

| 例外類型 | HTTP Status | 使用時機 |
|----------|-------------|----------|
| `ResourceNotFoundException` | 404 | Ticket 不存在 |
| `ForbiddenOperationException` | 403 | 無權限查詢此 Ticket 的 Audit Log |

### 7.2 權限驗證錯誤處理

```java
@Override
public PageResponse<AuditLogResponse> getAuditLogsByTicketId(
        UUID ticketId,
        Pageable pageable,
        CurrentUser currentUser
) {
    Ticket ticket = ticketRepository.findById(ticketId)
        .orElseThrow(() -> new ResourceNotFoundException(
            "Ticket not found: " + ticketId));

    // 驗證 Ticket 存取權限
    if (!hasReadPermission(ticket, currentUser)) {
        throw new ForbiddenOperationException(
            "No permission to view audit logs for this ticket");
    }

    // 查詢 Audit Logs
    Page<AuditLog> page = auditLogRepository.findByTicketIdOrderByCreatedAtDesc(
        ticketId, pageable);

    return PageResponse.from(page, this::enrichResponse);
}

private boolean hasReadPermission(Ticket ticket, CurrentUser currentUser) {
    return switch (currentUser.role()) {
        case "ADMIN" -> true;
        case "AGENT" -> ticket.getAssignedTo() != null
                && ticket.getAssignedTo().equals(currentUser.userId());
        case "CUSTOMER" -> ticket.getCreatedBy().equals(currentUser.userId());
        default -> false;
    };
}
```

### 7.3 錯誤訊息設計

| 情境 | 錯誤訊息 |
|------|----------|
| Ticket 不存在 | "Ticket not found: {ticketId}" |
| 無權限查詢 Audit Log | "No permission to view audit logs for this ticket" |

### 7.4 寫入失敗處理

Audit Log 寫入失敗時，Spring 會自動拋出 `DataAccessException`，導致整個 Transaction 回滾。

```java
// TicketServiceImpl.java
@Override
public TicketResponse createTicket(CreateTicketRequest request, UUID createdBy) {
    // ... 業務邏輯
    Ticket saved = ticketRepository.save(ticket);

    // 如果 Audit Log 寫入失敗，Transaction 會回滾
    // Ticket 也不會被保存
    auditLogRepository.save(AuditLog.create(
        createdBy,
        saved.getId(),
        AuditAction.TICKET_CREATED
    ));

    return enrichResponse(saved);
}
```

---

## 8. 資料庫 Migration

### ⚠️ Migration 策略

由於 V1__create_initial_schema.sql 中已存在一個通用型 `audit_logs` 表格，與本模組的專用設計衝突。

**Migration 策略**：單一 V7 Migration（DROP + CREATE 在同一檔案中）

### 8.1 V7：刪除舊表 + 建立新表

```sql
-- V7: 刪除舊表 + 建立新表
-- 由於 V1__create_initial_schema.sql 中已存在通用型 audit_logs 表格，
-- 與本模組的專用型設計不相容，因此需先刪除舊表再建立新表

-- 刪除舊的通用型 audit_logs 表格
DROP TABLE IF EXISTS audit_logs CASCADE;

-- 建立專用型 audit_logs 表格
CREATE TABLE audit_logs (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id        UUID            NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    ticket_id       UUID            NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    action          VARCHAR(30)     NOT NULL,
    field_name      VARCHAR(30),
    old_value       VARCHAR(500),
    new_value       VARCHAR(500),
    internal        BOOLEAN,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- 索引
CREATE INDEX idx_audit_logs_ticket_id ON audit_logs(ticket_id);
CREATE INDEX idx_audit_logs_actor_id ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
CREATE INDEX idx_audit_logs_ticket_created ON audit_logs(ticket_id, created_at DESC);
```

### ⚠️ Migration 警告

1. **資料丢失**：執行 Migration 後，舊的 `audit_logs` 資料將被刪除
2. **不可逆**：此操作無法撤銷，請確認後再執行

---

## 9. 技術決策摘要

| 決策 | 選擇 | 理由 |
|------|------|------|
| Entity 父類別 | `BaseEntity` | Audit Log 不可編輯，不需要 Version 欄位 |
| ID 型別 | UUID | 分散式安全，避免順序猜測 |
| 刪除 | 不提供 | 永久保存，確保稽核完整性 |
| 寫入方式 | 分散在業務 Service | 確保 Audit Log 與業務操作在同一 Transaction |
| oldValue/newValue | VARCHAR(500) | 足以儲存狀態、優先級、UUID 等常見值 |
| internal 標記 | 布林欄位 | 比 JSON metadata 更直觀，查詢效能更好 |
| 查詢方式 | JPA Repository | 簡單查詢，無需 Specification |
| 分頁支援 | PageResponse | 統一 API 回應格式 |

---

## 10. 測試策略

| 測試類型 | 目標 | 覆蓋重點 |
|----------|------|----------|
| `AuditLogServiceTest` | Service 商業邏輯 | Mock Repository；權限驗證邏輯 |
| `TicketAuditLogControllerTest` | API 端點 | HTTP 請求/回應驗證；角色權限 |
| `TicketServiceImplAuditTest` | 整合測試 | Audit Log 寫入驗證 |

### 10.1 權限測試重點

| 測試情境 | 預期行為 |
|----------|----------|
| Customer 查詢自己 Ticket 的 Audit Log | ✅ 成功 |
| Customer 查詢他人 Ticket 的 Audit Log | ❌ ForbiddenOperationException |
| Agent 查詢被指派 Ticket 的 Audit Log | ✅ 成功 |
| Agent 查詢未被指派 Ticket 的 Audit Log | ❌ ForbiddenOperationException |
| Admin 查詢任何 Ticket 的 Audit Log | ✅ 成功 |

### 10.2 寫入測試重點

| 測試情境 | 預期行為 |
|----------|----------|
| 建立 Ticket 時自動寫入 Audit Log | ✅ `TICKET_CREATED` |
| 變更狀態時自動寫入 Audit Log | ✅ `STATUS_CHANGED` + oldValue/newValue |
| 變更 Priority 時自動寫入 Audit Log | ✅ `PRIORITY_CHANGED` + oldValue/newValue |
| 指派 Agent 時自動寫入 Audit Log | ✅ `ASSIGNED` |
| 取消指派時自動寫入 Audit Log | ✅ `UNASSIGNED` |
| 新增 Comment 時自動寫入 Audit Log | ✅ `COMMENT_ADDED` + internal |
| Audit Log 寫入失敗時 Transaction 回滾 | ✅ Ticket 也不會被保存 |

---

## 11. 未來擴展方向（Out of Scope）

- Admin 全域 Audit Log 查詢端點（`/admin/audit-logs`）
- Audit Log Export 功能（CSV/Excel）
- 記錄 Title/Description 變更
- 記錄 Category 變更
- Attachment 新增事件（待 Attachment 功能實作）
- Audit Log 歸檔機制（歷史資料移轉）
- 異常行為偵測（如短時間內大量修改同一 Ticket）
