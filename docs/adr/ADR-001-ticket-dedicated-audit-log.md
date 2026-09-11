# ADR-001：Audit Log 採用 Ticket 專用設計而非通用設計

## 狀態

| 項目 | 內容 |
|------|------|
| **狀態** | ✅ 已接受 |
| **日期** | 2026-09-11 |
| **決定者** | PK |

---

## 背景

在設計 Audit Log 功能時，有兩種主要設計選項：

1. **通用型 Audit Log**：單一表格支援多實體（Ticket、Comment、User、Category 等）
2. **專用型 Audit Log**：針對 Ticket 設計的專屬表格

V1__create_initial_schema.sql 中已存在一個通用型 `audit_logs` 表格，與本計劃的專用設計衝突，需要重新评估設計決策。

---

## 決策

**採用 Ticket 專用型 Audit Log 設計**

```sql
CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id        UUID NOT NULL REFERENCES users(id),
    ticket_id       UUID NOT NULL REFERENCES tickets(id),
    action          VARCHAR(30) NOT NULL,
    field_name      VARCHAR(30),
    old_value       VARCHAR(500),
    new_value       VARCHAR(500),
    internal        BOOLEAN,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

## 理由

### 1. 主要用例是「查詢某張 ticket 的歷史」

Audit Log 的主要用途是：

| 角色 | 用例 |
|------|------|
| **Customer** | 查看 ticket 處理過程 |
| **Agent** | 了解 ticket 的完整脈絡 |
| **Admin** | 稽核 ticket 的處理是否合規 |

這些都是 **per-ticket** 的查詢，不是跨 entity 的查詢。

---

### 2. 範圍明確（只有 ticket 相關事件）

目前只定義了 8 個 ticket 相關事件：

| 事件 | Action 枚舉 |
|------|-------------|
| Ticket 建立 | `TICKET_CREATED` |
| 狀態變更 | `STATUS_CHANGED` |
| Priority 變更 | `PRIORITY_CHANGED` |
| Agent 指派 | `ASSIGNED` |
| 取消指派 | `UNASSIGNED` |
| Comment 新增 | `COMMENT_ADDED` |
| Ticket 解決 | `RESOLVED` |
| Ticket 關閉 | `CLOSED` |

沒有需要 audit User、Category 的變更。通用設計會過度工程化。

---

### 3. 查詢效能更好

**Ticket 專用設計的索引**：

```sql
CREATE INDEX idx_audit_logs_ticket_id ON audit_logs(ticket_id);
CREATE INDEX idx_audit_logs_ticket_created ON audit_logs(ticket_id, created_at DESC);
```

**查詢某張 ticket 的 audit trail**：

```sql
SELECT * FROM audit_logs
WHERE ticket_id = '...'
ORDER BY created_at DESC;
```

非常直接，不需要過濾 `entity_type`。

---

### 4. 前端顯示更容易

專用設計的 Response 結構清晰：

```json
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
}
```

若用通用設計，`old_values`、`new_values` 是 JSONB，前端需要額外解析。

---

### 5. 符合最佳實踐

對於特定領域（如 Ticket Management），建議使用 **per-table audit log**，而不是通用 audit log。

**通用 audit log 適合**：
- 低流量場景
- 需要共享審計的參考表
- 跨多個業務領域的統一稽核需求

---

## 替代方案

### 替代方案 A：通用型 Audit Log（已否決）

```sql
CREATE TABLE audit_logs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type    VARCHAR(100) NOT NULL,  -- TICKET/COMMENT/USER/CATEGORY
    entity_id      UUID NOT NULL,
    operation      VARCHAR(20) NOT NULL,   -- CREATE/UPDATE/DELETE
    actor_user_id  UUID REFERENCES users(id),
    old_values     JSONB,
    new_values     JSONB,
    changed_fields JSONB,
    ...
);
```

**否決理由**：
- 過度工程化，當前範圍只有 Ticket 相關事件
- 查詢需要額外過濾 `entity_type`
- 前端需要解析 JSONB
- 維護成本較高

### 替代方案 B：不做 Audit Log（已否決）

**否決理由**：
- 無法滿足可追溯性與可稽核性需求
- 無法處理爭議（如 Customer 投訴「沒人理我」）
- 缺乏安全性審計能力

---

## 何時才需要通用 Audit Log？

若未來系統擴展為以下場景，才值得引入通用 audit log：

1. **多 entity 都需要 audit** — Ticket、User、Category、Product、Order 等
2. **需要跨 entity 查詢** — 「某使用者的所有操作」
3. **需要統一的 audit 報表與合規稽核**

---

## 影響

### 正向影響

- ✅ 查詢效能更好（直接索引查詢）
- ✅ 前端解析更簡單（無需處理 JSONB）
- ✅ 程式碼維護更簡單（型別安全）
- ✅ 符合當前業務需求

### 需要注意的事項

- ⚠️ 遷移成本：需刪除舊的通用型 `audit_logs` 表格
- ⚠️ 未來擴展：若需求變更，需重新評估設計
- ⚠️ 資料丢失：Migration 會刪除舊資料

---

## 相關決策

| ADR | 決策 |
|-----|------|
| ADR-002 | Audit Log 與業務操作同一 Transaction |
| ADR-003 | oldValue/newValue 使用 VARCHAR(500) 而非 JSONB |
| ADR-004 | Comment internal 標記使用布林欄位 |

---

## 參考資料

- [Audit Logging Patterns](https://www.google.com/search?q=audit+log+table+design+patterns)
- [Spring Boot Audit](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.auditing)
- [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)

---

*ADR 版本：v1.0 | 2026-09-11 | 決定者：PK*
