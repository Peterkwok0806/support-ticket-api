# Comment / Internal Note 功能需求

## 1. 概述

Comment 系統讓 Customer、Agent、Admin 能在同一張 Ticket 上溝通，並透過 `internal` 欄位區分「客戶看得到的回覆」與「內部討論」。

所有 Comment 資料永久保存，不可編輯、不可刪除，以確保稽核需求。

---

## 2. 對使用者的價值

| 角色 | 價值 |
|------|------|
| **Customer** | 能看到 Agent 的正式回覆；能補充更多資訊（例如截圖描述、重現步驟）；了解問題處理進度 |
| **Agent** | 能給 Customer 正式回覆（public comment）；能與同事或主管做內部討論（internal note）；所有脈絡留在 Ticket 內，無需另外開 Slack/Email |
| **Admin** | 查看完整溝通紀錄（public + internal）；稽核 Agent 的處理過程；能在爭議時回溯當時的決策脈絡 |

---

## 3. 資料模型

### 3.1 Comment Entity

| 欄位 | 型別 | 說明 | 約束 |
|------|------|------|------|
| id | UUID | 主鍵 | 自動生成，繼承 BaseEntity |
| ticketId | UUID | 所屬工單 | FK，非空 |
| authorId | UUID | 留言作者 | FK，非空 |
| content | String | 留言內容 | TEXT，非空，最大 10000 字 |
| internal | boolean | 是否內部留言 | 預設 false |
| createdAt | Instant | 建立時間 | 自動設定 |
| updatedAt | Instant | 更新時間 | 自動設定 |

### 3.2 資料庫 Migration

```sql
CREATE TABLE comments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id       UUID NOT NULL REFERENCES tickets(id),
    author_id       UUID NOT NULL REFERENCES users(id),
    content         TEXT NOT NULL,
    internal        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_comments_ticket_id ON comments(ticket_id);
CREATE INDEX idx_comments_ticket_internal ON comments(ticket_id, internal);
```

### 3.3 設計原則

- **不繼承 VersionedEntity**：Comment 不可編輯，不需要樂觀鎖
- **永久保存**：不可刪除，確保稽核完整性
- **複合索引**：針對 `ticket_id + internal` 建立索引，加速依角色過濾查詢

---

## 4. 角色權限矩陣

| 操作 | CUSTOMER | AGENT | ADMIN |
|------|:--------:|:-----:|:-----:|
| 新增 Public Comment（internal=false） | ✅ 自己的 Ticket | ✅ 被指派的 Ticket | ✅ 所有 Ticket |
| 新增 Internal Note（internal=true） | ❌ | ✅ 被指派的 Ticket | ✅ 所有 Ticket |
| 讀取 Public Comment（internal=false） | ✅ 自己的 Ticket | ✅ 被指派的 Ticket | ✅ 所有 Ticket |
| 讀取 Internal Note（internal=true） | ❌ | ✅ 被指派的 Ticket | ✅ 所有 Ticket |
| 刪除 Comment | ❌ | ❌ | ❌ |

### 4.1 權限說明

- **CUSTOMER**：只能對自己建立的 Ticket 留言；只能建立 public comment；無法看到 internal note
- **AGENT**：只能對被指派給自己的 Ticket 留言；可以建立 public comment 或 internal note；可以看到全部 comment
- **ADMIN**：可以對所有 Ticket 留言；可以建立 public comment 或 internal note；可以看到全部 comment

---

## 5. 功能需求

### 5.1 新增 Comment（Create）

- **角色**：ALL（依業務規則過濾）
- **必填欄位**：content
- **可選欄位**：internal（預設 false）
- **建立時自動設定**：
  - `ticketId = 路徑參數`
  - `authorId = currentUser`
  - `createdAt = now()`
  - `internal = request.internal() ?: false`

### 5.2 查詢 Comment 列表（Read List）

- **角色**：ALL（依業務規則過濾）
- **查詢條件**：ticketId（路徑參數）
- **權限過濾**：
  - ADMIN / AGENT：回傳全部 comment
  - CUSTOMER：僅回傳 `internal = false`
- **排序**：依 `createdAt` 遞增（時間順序）

### 5.3 查詢單一 Comment（Read One）

- **角色**：ALL（依業務規則過濾）
- **邏輯**：先查詢 comment，若為 internal 且 currentUser 為 CUSTOMER，則拋出 ForbiddenOperationException

---

## 6. API 端點

### 6.1 CommentController（`/api/v1/tickets/{ticketId}/comments`）

| Method | Path | 角色 | 說明 |
|--------|------|------|------|
| POST | / | ALL（依業務規則） | 新增 Comment |
| GET | / | ALL（依商業規則） | 取得 Comment 列表 |
| GET | /{id} | ALL（依商業規則） | 取得單一 Comment |

### 6.2 Request / Response 格式

#### CreateCommentRequest

```java
public record CreateCommentRequest(
    @NotBlank(message = "Content is required")
    @Size(max = 10000, message = "Content must not exceed 10000 characters")
    String content,

    // 可選，預設 false
    // Customer 傳入 true 時，拋出 ForbiddenOperationException
    Boolean internal
) {}
```

#### CommentResponse

```java
public record CommentResponse(
    UUID id,
    UUID ticketId,
    UUID authorId,
    String authorName,      // 展示用名稱
    String content,
    boolean internal,
    Instant createdAt,
    Instant updatedAt
) {}
```

### 6.3 錯誤回應

| 例外類型 | HTTP Status | 觸發條件 |
|----------|-------------|----------|
| ResourceNotFoundException | 404 | Ticket 不存在 或 Comment 不存在 |
| ForbiddenOperationException | 403 | 無權限留言、無法看到 internal note、Customer 嘗試建立 internal note |

---

## 7. 錯誤訊息設計

| 情境 | 錯誤訊息 |
|------|----------|
| 無權限對此 Ticket 留言 | "No permission to comment on this ticket" |
| Customer 嘗試建立 Internal Note | "Customer cannot create internal notes" |
| Customer 嘗試讀取 Internal Note | "No permission to view this comment" |

---

## 8. 非功能性需求

- 使用 JPA Repository 支援查詢
- 唯讀查詢使用 `@Transactional(readOnly = true)`
- Comment 查詢需與 Ticket 的讀取權限一致
- 所有寫入操作包在 Transaction 中

---

## 9. Out of Scope（本次不實作）

- Comment 編輯功能
- Comment 刪除功能（永久保存）
- Comment 附件上傳
- Comment 通知系統
- Comment 稽核日誌

---

## 10. 預計產出檔案

```
src/main/java/com/pk/support_ticket_api/
└── comments/
    ├── domain/
    │   └── Comment.java
    ├── dto/
    │   ├── CreateCommentRequest.java
    │   └── CommentResponse.java
    ├── repository/
    │   └── CommentRepository.java
    ├── service/
    │   ├── CommentService.java
    │   └── CommentServiceImpl.java
    └── web/
        └── CommentController.java

src/main/resources/db/migration/
└── V{version}__create_comments_table.sql

src/test/java/com/pk/support_ticket_api/comments/
├── service/
│   └── CommentServiceTest.java
└── web/
    └── CommentControllerTest.java
```

---

## 11. 驗收標準

- [ ] Customer 可以對自己的 Ticket 新增 Public Comment
- [ ] Customer 無法建立 Internal Note（回傳 403）
- [ ] Customer 無法看到 Internal Note（回傳 403）
- [ ] Customer 無法對他人的 Ticket 留言（回傳 403）
- [ ] Agent 可以對被指派的 Ticket 新增 Public Comment
- [ ] Agent 可以對被指派的 Ticket 新增 Internal Note
- [ ] Agent 無法對未指派的 Ticket 留言（回傳 403）
- [ ] Admin 可以對所有 Ticket 新增 Public / Internal Comment
- [ ] 讀取 Comment 列表時，ADMIN / AGENT 可看到全部，CUSTOMER 僅看到 public
- [ ] 讀取單一 Internal Note 時，CUSTOMER 收到 403
- [ ] 所有 Comment 都會記錄 authorId 與 createdAt
- [ ] 刪除 API 不存在
- [ ] 通過既有測試（Build、Test、Lint）
- [ ] API 文件（Swagger）正確產出
