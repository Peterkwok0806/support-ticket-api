# Audit Log 功能建置計劃書

## 文件資訊

| 項目 | 內容 |
|------|------|
| 計劃版本 | v1.2 |
| 日期 | 2026-09-11 |
| 狀態 | ✅ 已確認 |
| 更新 | 改為單一 V7 Migration |

---

## 1. 需求摘要

### 1.1 功能目標

完整記錄「誰在什麼時候對哪張 ticket 做了什麼操作、改了什麼欄位」，讓系統具備可追溯性與可稽核性。

### 1.2 價值矩陣

| 角色 | 價值 |
|------|------|
| **Customer** | 看到 ticket 關鍵歷史（建立、狀態變更、指派），建立信任感 |
| **Agent** | 理解 ticket 處理脈絡，交接時有完整歷史可參考 |
| **Admin** | 稽核團隊行為、檢測異常操作、處理爭議 |

### 1.3 技術價值

- **資料完整性**：可回溯「誰、何時、什麼欄位、舊值→新值」
- **安全合規**：檢測未授權存取或異常行為
- **除錯分析**：快速定位 API 呼叫、區分 bug 或人為操作

---

## 2. 現有資源盤點

### 2.1 已有基礎設施

| 資源 | 狀態 | 說明 |
|------|------|------|
| `AuditAction` enum | ⚠️ 需修改 | 存在但需移除 `REOPENED` |
| 模組目錄結構 | ✅ 可用 | `backend/audit/` 已規劃 |
| 分層架構模式 | ✅ 可用 | Controller → Service → Repository |
| `CurrentUser` | ✅ 可用 | Spring Security 注入當前登入者 |
| `VersionedEntity` | ✅ 可用 | 主鍵 + createdAt + updatedAt |
| Flyway Migration | ✅ 可用 | 資料庫版本管理 |
| `PageResponse` | ✅ 可用 | 統一分頁格式 |

### 2.2 現有 Schema 衝突

⚠️ **重要**：`V1__create_initial_schema.sql` 中已存在一個通用型 `audit_logs` 表格，設計如下：

```sql
CREATE TABLE audit_logs (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type    VARCHAR(100) NOT NULL,  -- TICKET/COMMENT/USER/CATEGORY
    entity_id      UUID         NOT NULL,
    operation      VARCHAR(20)  NOT NULL,  -- CREATE/UPDATE/DELETE
    actor_user_id  UUID         REFERENCES users(id),
    old_values     JSONB,
    new_values     JSONB,
    changed_fields JSONB,
    ...
);
```

**衝突點**：
- V1 為通用型設計（entity_type/entity_id）
- 本計劃為專用型設計（ticket_id + action）
- 兩者不相容，需刪除重建

### 2.3 Migration 策略

採用 **Clean Drop** 策略：
- V7：刪除舊的通用型 `audit_logs` 表格
- V8：建立新的專用型 `audit_logs` 表格
- 原有資料將被刪除（如有需要請先備份）

---

## 3. 事件清單（需記錄的操作）

### 3.1 最終事件定義（8 項）

| 事件 | Action 枚舉 | 觸發時機 | 需記錄的值 |
|------|-------------|----------|------------|
| Ticket 建立 | `TICKET_CREATED` | POST /tickets | — |
| 狀態變更 | `STATUS_CHANGED` | PATCH /tickets/{id}/status | oldValue → newValue |
| Priority 變更 | `PRIORITY_CHANGED` | PATCH /tickets/{id} | oldValue → newValue |
| Agent 指派 | `ASSIGNED` | PATCH /tickets/{id}/assign | assigneeId |
| 取消指派 | `UNASSIGNED` | PATCH /tickets/{id}/assign (設為 null) | — |
| Comment 新增 | `COMMENT_ADDED` | POST /tickets/{id}/comments | `internal` 布林欄位 |
| Ticket 解決 | `RESOLVED` | 狀態變更為 RESOLVED | — |
| Ticket 關閉 | `CLOSED` | 狀態變更為 CLOSED | — |

### 3.2 明確不記錄的事件

| 事件 | 原因 |
|------|------|
| REOPENED | Ticket 無 reopen 功能 |
| ATTACHMENT_ADDED | Attachment 功能尚未實作 |
| Title/Description 變更 | 規格未定義 |
| Category 變更 | 規格未定義 |

---

## 4. Domain Model

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
    private AuditFieldName fieldName;  // 可選枚舉

    // 變更前的值（字串表示）
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

### 4.2 AuditFieldName 枚舉

```java
public enum AuditFieldName {
    STATUS,
    PRIORITY,
    ASSIGNEE
}
```

### 4.3 AuditAction 枚舉（修改：移除 REOPENED）

```java
public enum AuditAction {
    TICKET_CREATED,
    STATUS_CHANGED,
    PRIORITY_CHANGED,
    ASSIGNED,
    UNASSIGNED,
    COMMENT_ADDED,
    ATTACHMENT_ADDED,  // 預留，尚未實作
    RESOLVED,
    CLOSED
    // REOPENED 已移除
}
```

---

## 5. 資料庫 Migration

### ⚠️ Migration 策略

由於 V1__create_initial_schema.sql 中已存在一個通用型 `audit_logs` 表格，與本計劃的專用設計衝突，需要執行以下 Migration：

| 選項 | 說明 | 適用情境 |
|------|------|----------|
| **Clean Drop（採用）** | 刪除舊表格重建，**資料會丢失** | 尚未正式使用或可接受資料丢失 |

### 5.1 Migration（單一 V7）

```
V7__create_audit_logs_table.sql  ← 刪除舊表 + 建立新表
```

### 5.2 V7：刪除舊表 + 建立新表

```sql
-- V7: 刪除舊表 + 建立新表
-- 由於 V1__create_initial_schema.sql 中已存在通用型 audit_logs 表格，
-- 與本計劃的專用型設計不相容，因此需先刪除舊表再建立新表

-- 刪除舊的通用型 audit_logs 表格
DROP TABLE IF EXISTS audit_logs CASCADE;

-- 建立專用型 audit_logs 表格
-- 用於記錄 Ticket 相關的所有重要操作，支援可追溯性與可稽核性
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

-- 註解
COMMENT ON TABLE audit_logs IS '操作稽核日誌，記錄所有重要操作';
COMMENT ON COLUMN audit_logs.actor_id IS '執行操作的使用者 ID';
COMMENT ON COLUMN audit_logs.ticket_id IS '操作發生的 Ticket ID';
COMMENT ON COLUMN audit_logs.action IS '操作類型（TICKET_CREATED/STATUS_CHANGED/PRIORITY_CHANGED/ASSIGNED/UNASSIGNED/COMMENT_ADDED/RESOLVED/CLOSED）';
COMMENT ON COLUMN audit_logs.field_name IS '變更的欄位名稱（STATUS/PRIORITY/ASSIGNEE），非欄位變更時可為空';
COMMENT ON COLUMN audit_logs.old_value IS '變更前的值';
COMMENT ON COLUMN audit_logs.new_value IS '變更後的值';
COMMENT ON COLUMN audit_logs.internal IS '是否為內部操作，用於 COMMENT_ADDED 事件';
COMMENT ON COLUMN audit_logs.created_at IS '操作發生的時間';

-- 索引設計
CREATE INDEX idx_audit_logs_ticket_id ON audit_logs(ticket_id);
CREATE INDEX idx_audit_logs_actor_id ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
CREATE INDEX idx_audit_logs_ticket_created ON audit_logs(ticket_id, created_at DESC);
```

### ⚠️ Migration 執行警告

1. **資料丢失**：V7 Migration 會刪除舊的 `audit_logs` 表格，所有現有資料將被刪除
2. **依賴檢查**：確保沒有其他程式碼依賴舊的 `audit_logs` 表格結構
3. **備份建議**：如有需要，先備份舊資料

---

## 6. API 設計

### 6.1 端點（第一版）

| Method | Path | 角色 | 說明 |
|--------|------|------|------|
| GET | `/api/v1/tickets/{ticketId}/audit-logs` | 依 Ticket 權限 | 查詢單張 Ticket 的審計日誌 |

> **注意**：`/admin/audit-logs` 全域查詢端點第一版不實作，未來再考慮。

### 6.2 Query Parameters

```
GET /api/v1/tickets/{ticketId}/audit-logs
  ?page=0
  &size=20
  &sort=createdAt,desc
```

### 6.3 Response 格式

```json
{
  "content": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "actorId": "...",
      "actorName": "John Agent",
      "ticketId": "...",
      "action": "STATUS_CHANGED",
      "fieldName": "STATUS",
      "oldValue": "OPEN",
      "newValue": "IN_PROGRESS",
      "internal": null,
      "createdAt": "2026-09-11T10:30:00Z"
    },
    {
      "id": "...",
      "actorId": "...",
      "actorName": "Jane Agent",
      "ticketId": "...",
      "action": "COMMENT_ADDED",
      "fieldName": null,
      "oldValue": null,
      "newValue": null,
      "internal": true,
      "createdAt": "2026-09-11T11:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 45,
  "totalPages": 3,
  "first": true,
  "last": false
}
```

### 6.4 權限設計

| 角色 | 可查詢範圍 |
|------|-----------|
| ADMIN | 所有 Audit Logs |
| AGENT | 被指派 Ticket 的 Audit Logs |
| CUSTOMER | 自己建立 Ticket 的 Audit Logs |

> **原則**：Audit Log 的可見範圍與 Ticket 本身一致。

---

## 7. Service 整合策略

### 7.1 設計原則

**Audit Log 是業務完整性的一部分，必須在同一 Transaction 內完成。**

```
業務操作失敗 → Audit Log 自然回滾
Audit Log 寫入失敗 → 業務操作也失敗（由同一 Transaction 保證）
```

### 7.2 實作方式

直接注入 `AuditLogRepository` 到需要記錄的 Service：

```java
// TicketServiceImpl.java
@Service
@RequiredArgsConstructor
@Transactional
public class TicketServiceImpl implements TicketService {

    private final TicketRepository ticketRepository;
    private final AuditLogRepository auditLogRepository;  // 新增

    @Override
    public TicketResponse createTicket(CreateTicketRequest request, UUID createdBy) {
        // ... 業務邏輯
        Ticket saved = ticketRepository.save(ticket);

        // 記錄 Audit Log（同一 Transaction）
        auditLogRepository.save(AuditLog.create(
            actorId: createdBy,
            ticketId: saved.getId(),
            action: AuditAction.TICKET_CREATED
        ));

        return enrichResponse(saved);
    }

    @Override
    public TicketResponse updateStatus(UUID id, TicketStatusUpdateRequest request, CurrentUser currentUser) {
        Ticket ticket = findTicketById(id);

        // 驗證邏輯...

        TicketStatus oldStatus = ticket.getStatus();
        ticket.setStatus(request.status());
        handleStatusSideEffects(ticket, request.status());

        // 記錄 Audit Log
        AuditLog audit = AuditLog.createFieldChange(
            actorId: currentUser.userId(),
            ticketId: ticket.getId(),
            action: AuditAction.STATUS_CHANGED,
            fieldName: AuditFieldName.STATUS,
            oldValue: oldStatus.name(),
            newValue: request.status().name()
        );
        auditLogRepository.save(audit);

        // 業務操作
        Ticket saved = ticketRepository.save(ticket);
        return enrichResponse(saved);
    }
}
```

### 7.3 AuditLog Factory Method

```java
// AuditLog.java
public class AuditLog {
    // ... fields, getters, setters

    public static AuditLog create(UUID actorId, UUID ticketId, AuditAction action) {
        AuditLog log = new AuditLog();
        log.setActorId(actorId);
        log.setTicketId(ticketId);
        log.setAction(action);
        return log;
    }

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

### 7.4 需要修改的 Service

| Service | 需修改的方法 | 記錄事件 |
|---------|-------------|----------|
| `TicketServiceImpl` | `createTicket()` | `TICKET_CREATED` |
| `TicketServiceImpl` | `updateTicket()` | `PRIORITY_CHANGED`（當 priority 變更時） |
| `TicketServiceImpl` | `updateStatus()` | `STATUS_CHANGED` |
| `TicketServiceImpl` | `assignTicket()` | `ASSIGNED` / `UNASSIGNED` |
| `CommentServiceImpl` | `createComment()` | `COMMENT_ADDED`（含 `internal` 欄位）|

---

## 8. 預計產出檔案

### 8.1 新增檔案（11 個）

```
src/main/java/com/pk/support_ticket_api/
├── audit/
│   ├── domain/
│   │   ├── AuditLog.java              # Entity
│   │   ├── AuditFieldName.java        # 枚舉（新增）
│   │   └── AuditAction.java          # 枚舉（修改：移除 REOPENED）
│   ├── dto/
│   │   ├── AuditLogResponse.java     # 查詢響應
│   │   └── AuditLogFilterRequest.java # 過濾條件（預留）
│   ├── repository/
│   │   └── AuditLogRepository.java    # JPA Repository
│   ├── service/
│   │   ├── AuditLogService.java       # 介面
│   │   └── AuditLogServiceImpl.java   # 實作
│   └── web/
│       └── TicketAuditLogController.java  # GET /tickets/{id}/audit-logs

src/main/resources/db/migration/
└── V7__create_audit_logs_table.sql

src/test/java/com/pk/support_ticket_api/
└── audit/
    ├── service/
    │   └── AuditLogServiceTest.java       # Unit Test
    └── web/
        └── TicketAuditLogControllerTest.java  # Integration Test
```

### 8.2 修改檔案（3 個）

| 檔案 | 修改內容 |
|------|----------|
| `AuditAction.java` | 移除 `REOPENED` |
| `TicketServiceImpl.java` | 注入 `AuditLogRepository`，實作 Audit Log 寫入 |
| `CommentServiceImpl.java` | 注入 `AuditLogRepository`，實作 Audit Log 寫入 |

---

## 9. 實作順序

### Phase 1：基礎設施（Foundation）

| Step | Task | 預計時程 |
|------|------|----------|
| 1.1 | 修改 `AuditAction.java` — 移除 `REOPENED` | - |
| 1.2 | 建立 `AuditFieldName.java` 枚舉 | - |
| 1.3 | 建立 `AuditLog.java` Entity | - |
| 1.4 | 建立 `AuditLogRepository.java` | - |
| 1.5 | 建立 `V7__create_audit_logs_table.sql` | - |
| 1.6 | 建立 `AuditLogResponse.java` DTO | - |
| 1.7 | 建立 `AuditLogFilterRequest.java` DTO（預留） | - |

### Phase 2：查詢 API（Query API）

| Step | Task | 預計時程 |
|------|------|----------|
| 2.1 | 建立 `AuditLogService` 介面 | - |
| 2.2 | 建立 `AuditLogServiceImpl` 實作 | - |
| 2.3 | 建立 `TicketAuditLogController.java` | - |
| 2.4 | 撰寫 Unit Test | - |

### Phase 3：寫入整合（Write Integration）

| Step | Task | 預計時程 |
|------|------|----------|
| 3.1 | 修改 `TicketServiceImpl` — 注入 `AuditLogRepository` | - |
| 3.2 | 在 `createTicket()` 加入 Audit Log | - |
| 3.3 | 在 `updateStatus()` 加入 Audit Log | - |
| 3.4 | 在 `assignTicket()` 加入 Audit Log | - |
| 3.5 | 在 `updateTicket()` 加入 Audit Log（Priority 變更） | - |
| 3.6 | 修改 `CommentServiceImpl` — 加入 Audit Log | - |

### Phase 4：驗證（Verification）

| Task | 說明 |
|------|------|
| 執行 Migration | 確認資料庫結構正確 |
| 單元測試 | AuditLogServiceTest |
| 整合測試 | TicketServiceImpl + AuditLogRepository |
| API 測試 | Controller Test |

---

## 10. 測試策略

### 10.1 Unit Test：AuditLogServiceTest

```java
@Test
void getAuditLogsByTicketId_withCustomerRole_shouldReturnOnlyOwnTickets() {
    // Given
    UUID customerId = UUID.randomUUID();
    UUID otherTicketId = UUID.randomUUID();

    // Customer 只能看到自己 Ticket 的 Audit Logs
    // 測試權限過濾邏輯
}

@Test
void getAuditLogsByTicketId_withInvalidTicket_shouldThrowException() {
    // Given
    UUID nonExistentTicketId = UUID.randomUUID();

    // When/Then
    assertThrows(ResourceNotFoundException.class, ...);
}
```

### 10.2 Integration Test：TicketServiceImpl Audit Integration

```java
@Test
void createTicket_shouldAlsoCreateAuditLog() {
    // Given
    CreateTicketRequest request = new CreateTicketRequest(...);

    // When
    TicketResponse response = ticketService.createTicket(request, customerId);

    // Then
    List<AuditLog> logs = auditLogRepository.findByTicketId(response.id());
    assertThat(logs).hasSize(1);
    assertThat(logs.get(0).getAction()).isEqualTo(AuditAction.TICKET_CREATED);
}

@Test
void updateStatus_shouldRecordOldAndNewValue() {
    // Given
    Ticket ticket = createTestTicket(TicketStatus.OPEN);

    // When
    ticketService.updateStatus(ticket.getId(),
        new TicketStatusUpdateRequest(TicketStatus.IN_PROGRESS),
        currentUser);

    // Then
    List<AuditLog> logs = auditLogRepository.findByTicketId(ticket.getId());
    AuditLog statusLog = logs.stream()
        .filter(l -> l.getAction() == AuditAction.STATUS_CHANGED)
        .findFirst()
        .orElseThrow();

    assertThat(statusLog.getOldValue()).isEqualTo("OPEN");
    assertThat(statusLog.getNewValue()).isEqualTo("IN_PROGRESS");
}
```

---

## 11. 決策記錄（Architecture Decision Records）

### ADR-001：Audit Log 與業務操作同一 Transaction

| 項目 | 內容 |
|------|------|
| **決定** | Audit Log 必須在業務操作的同一個 Transaction 內完成 |
| **原因** | Audit Log 是業務完整性的必要組成部分，不是可選的附帶功能 |
| **後果** | 業務失敗時 Audit Log 自然回滾；Audit Log 寫入失敗時業務操作也失敗 |
| **實作** | 直接注入 `AuditLogRepository` 到需要記錄的 Service |

### ADR-002：oldValue/newValue 儲存格式

| 項目 | 內容 |
|------|------|
| **決定** | 使用 VARCHAR(500) 類型儲存 |
| **原因** | 儲存字串表示；500 長度足以應付常見值（狀態、優先級、UUID） |
| **替代方案** | TEXT 類型（過度設計） |

### ADR-003：Comment internal 標記方式

| 項目 | 內容 |
|------|------|
| **決定** | 直接增加 `internal` 布林欄位 |
| **原因** | 比 JSON metadata 更直觀，查詢效能更好 |
| **替代方案** | `metadata: {isInternal: true}`（不夠直觀） |

### ADR-004：第一版不實作 Admin 全域查詢

| 項目 | 內容 |
|------|------|
| **決定** | 第一版只實作 `/tickets/{id}/audit-logs` 端點 |
| **原因** | Admin 全域查詢需求不明確，預留擴展彈性 |
| **未來** | 根據需求再考慮 `/admin/audit-logs` 端點 |

### ADR-005：不記錄 REOPENED 事件

| 項目 | 內容 |
|------|------|
| **決定** | 移除 AuditAction.REOPENED |
| **原因** | Ticket 規格中未定義 reopen 功能 |
| **影響** | 無法追蹤「從 CLOSED 回到其他狀態」的操作（因為規格不允許）|

---

## 12. 風險與限制

### 12.1 已知限制

| 限制 | 說明 | 緩解措施 |
|------|------|----------|
| 不記錄 Title/Description 變更 | 規格未定義 | 未來可擴展 `AuditFieldName.TITLE/DESCRIPTION` |
| 不記錄 Category 變更 | 規格未定義 | 未來可擴展 |
| 不記錄 Attachment | Attachment 功能尚未實作 | 預留 `AuditAction.ATTACHMENT_ADDED` 枚舉 |
| 無 Admin 全域查詢 | 第一版不實作 | 未來可擴展 |

### 12.2 效能考量

| 考量 | 說明 |
|------|------|
| 寫入頻率 | 每筆業務操作寫入 1 筆 Audit Log，頻率等同 Ticket 操作 |
| 查詢效能 | 建立複合索引 `idx_audit_logs_ticket_created` 優化常見查詢 |
| 儲存空間 | Audit Log 條目預估：每張 Ticket 平均 5-10 筆 |

---

## 13. 確認事項（已確認）

### 13.1 事件範圍
- [x] 只記錄本文件定義的 8 個事件
- [x] 不記錄 Title/Description/Category 變更
- [x] 不記錄 REOPENED

### 13.2 技術設計
- [x] oldValue/newValue 使用 VARCHAR(500) 格式
- [x] Audit Log 寫入失敗時業務操作一起失敗（同一 Transaction）

### 13.3 API 設計
- [x] GET `/tickets/{id}/audit-logs` 端點
- [x] Admin 全域查詢端點第一版不需要
- [x] 權限設計（依 Ticket 權限過濾）

### 13.4 Comment 內部標記
- [x] 使用 `internal` 布林欄位 + 一般的 `COMMENT_ADDED` 事件

---

*計劃書版本：v1.0 | 2026-09-11 | ✅ 已確認，可開始實作*
