  # Comment 模組架構說明

## 1. 架構目標與限制

### 1.1 設計目標

| 目標 | 說明 |
|------|------|
| 領域驅動設計 | 以 Domain 為核心，確保商業邏輯內聚於 Comment 模組 |
| 分層架構 | 嚴格遵守 Controller → Service → Repository 分層 |
| 權限控制 | 角色型權限控制（CUSTOMER / AGENT / ADMIN），防止未授權存取 |
| 可測試性 | 所有商業邏輯可透過單元測試驗證 |
| 一致性錯誤處理 | 統一使用既有 Exception 體系 |
| 永久保存 | Comment 不可編輯、不可刪除，確保稽核完整性 |

### 1.2 約束條件

| 約束 | 說明 |
|------|------|
| 依賴方向 | 外部依賴（如 Repository、External Service）注入 Service，不可直接引入 Domain |
| 事務邊界 | Service 層為事務邊界，所有寫入操作皆在 `@Transactional` 內 |
| 唯讀優化 | 查詢操作標記 `@Transactional(readOnly = true)` |
| 封裝性 | Entity 欄位僅透過 Getter/Setter 存取，不暴露內部實作 |
| 不可變性 | Comment 建立後不可修改、不可刪除 |

---

## 2. 模組邊界與責任

### 2.1 模組結構

```
comments/
├── domain/           # 領域模型（核心）
├── dto/              # 資料傳輸物件
├── repository/       # 資料存取
├── service/          # 商業邏輯
└── web/              # API 端點
```

### 2.2 責任歸屬

| 層 | 類別 | 責任 |
|----|------|------|
| **domain** | `Comment` | 純領域實體，僅承載狀態與資料，不含商業邏輯 |
| **dto** | Request DTOs | 接收客戶端輸入，執行 JSR-380 驗證 |
| **dto** | Response DTOs | 格式化輸出，隔離 Entity 與 API 契約 |
| **repository** | `CommentRepository` | 資料庫存取，僅暴露查詢方法 |
| **service** | `CommentService` | 商業邏輯編排，協調 Domain、DTO、Repository；**執行權限驗證** |
| **web** | Controllers | HTTP 協定處理，請求轉發；**使用 @PreAuthorize 標註角色權限** |

### 2.3 不應出現的職責

| 位置 | 不應包含 |
|------|----------|
| Controller | 商業邏輯、資料庫查詢、權限驗證 |
| Repository | 商業邏輯、業務規則 |
| Entity | 跨領域邏輯、HTTP 處理 |
| DTO | 商業邏輯、資料庫存取 |

---

## 3. 依賴方向

### 3.1 依賴關係圖

```
┌─────────────────────────────────────────────────────────┐
│                      web/                                │
│               (CommentController)                        │
│                                                          │
│              depends on: CommentService                   │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│                      service/                            │
│              (CommentServiceImpl)                        │
│                                                          │
│  depends on: Comment, CommentRepository, TicketRepository│
└──────────────────────┬──────────────────────────────────┘
                       │
        ┌──────────────┴──────────────┐
        ▼                              ▼
┌───────────────────┐      ┌───────────────────┐
│     domain/       │      │    repository/    │
│    Comment        │      │ CommentRepository  │
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
comments.service ──► tickets.repository（驗證 Ticket 存在）
comments.service ──► users.repository（取得作者名稱）
```

---

## 4. 核心資料模型的責任歸屬

### 4.1 Comment Entity

```java
@Entity
public class Comment extends BaseEntity {
    // 資料欄位（ticketId, authorId, content, internal）
    // Getter/Setter（由 Lombok 生成）
    // 不含商業邏輯
}
```

**責任**：
- 承載 Comment 的所有屬性
- 提供資料讀寫接口
- 由 JPA 管理生命週期

**不含**：
- 權限驗證邏輯
- 內容過濾邏輯
- 跨領域操作

**設計決策**：
- 繼承 `BaseEntity`（非 `VersionedEntity`），因為 Comment 不可編輯
- 不需要樂觀鎖，避免併發編輯衝突的複雜性

---

## 5. 主要資料流程

### 5.1 新增 Comment（Customer）

```
Client POST /api/v1/tickets/{ticketId}/comments
         Body: { "content": "...", "internal": false }
         JWT: CUSTOMER
         │
         ▼
┌─────────────────────────────────────┐
│  @PreAuthorize 角色驗證             │ ◄── 所有已登入角色皆可存取
│  CommentController.create()          │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     CommentServiceImpl              │
│  1. validate ticket exists          │
│  2. validate customer owns ticket  │ ◄── 驗證 createdBy = currentUser
│  3. validate internal = false       │ ◄── Customer 不能建立 internal note
│  4. create Comment                  │
│  5. save via repository             │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     CommentRepository               │
│  INSERT INTO comments (...)         │
└─────────────────────────────────────┘
```

### 5.2 新增 Comment（Agent - Public）

```
Client POST /api/v1/tickets/{ticketId}/comments
         Body: { "content": "...", "internal": false }
         JWT: AGENT
         │
         ▼
┌─────────────────────────────────────┐
│  @PreAuthorize 角色驗證             │
│  CommentController.create()          │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     CommentServiceImpl              │
│  1. validate ticket exists          │
│  2. validate agent is assigned     │ ◄── 驗證 assignedTo = currentUser
│  3. validate internal = false       │
│  4. create Comment                  │
│  5. save via repository             │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     CommentRepository               │
│  INSERT INTO comments (...)         │
└─────────────────────────────────────┘
```

### 5.3 新增 Comment（Agent - Internal Note）

```
Client POST /api/v1/tickets/{ticketId}/comments
         Body: { "content": "...", "internal": true }
         JWT: AGENT
         │
         ▼
┌─────────────────────────────────────┐
│  @PreAuthorize 角色驗證             │
│  CommentController.create()          │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     CommentServiceImpl              │
│  1. validate ticket exists          │
│  2. validate agent is assigned     │
│  3. validate internal = true        │ ◄── Agent 可建立 internal note
│  4. create Comment                  │
│  5. save via repository             │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     CommentRepository               │
│  INSERT INTO comments (...)         │
└─────────────────────────────────────┘
```

### 5.4 新增 Comment（Admin）

```
Client POST /api/v1/tickets/{ticketId}/comments
         Body: { "content": "...", "internal": true }
         JWT: ADMIN
         │
         ▼
┌─────────────────────────────────────┐
│  @PreAuthorize 角色驗證             │
│  CommentController.create()          │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     CommentServiceImpl              │
│  1. validate ticket exists          │
│  2. admin bypass all restrictions   │ ◄── ADMIN 可對所有 Ticket 留言
│  3. create Comment                  │
│  4. save via repository             │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     CommentRepository               │
│  INSERT INTO comments (...)         │
└─────────────────────────────────────┘
```

### 5.5 查詢 Comment 列表（Customer）

```
Client GET /api/v1/tickets/{ticketId}/comments
         JWT: CUSTOMER
         │
         ▼
┌─────────────────────────────────────┐
│  @PreAuthorize 角色驗證             │
│  CommentController.findAll()         │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     CommentServiceImpl              │
│  1. validate ticket exists          │
│  2. validate customer owns ticket  │
│  3. fetch comments                  │
│  4. filter: internal = false        │ ◄── Customer 只能看到 public comment
│  5. enrich authorName              │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     CommentRepository               │
│  SELECT * FROM comments            │
│    WHERE ticket_id = ?             │
│      AND internal = false          │
└─────────────────────────────────────┘
```

### 5.6 查詢 Comment 列表（Agent / Admin）

```
Client GET /api/v1/tickets/{ticketId}/comments
         JWT: AGENT or ADMIN
         │
         ▼
┌─────────────────────────────────────┐
│  @PreAuthorize 角色驗證             │
│  CommentController.findAll()         │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     CommentServiceImpl              │
│  1. validate ticket exists          │
│  2. validate access permission     │
│  3. fetch ALL comments              │ ◄── 不過濾 internal
│  4. enrich authorName              │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     CommentRepository               │
│  SELECT * FROM comments            │
│    WHERE ticket_id = ?             │
│    ORDER BY created_at ASC         │
└─────────────────────────────────────┘
```

---

## 6. 介面設計原則

### 6.1 Service 介面（契約）

```java
public interface CommentService {

    // 新增 Comment（依 currentUser 執行權限驗證）
    CommentResponse createComment(
        UUID ticketId,
        CreateCommentRequest request,
        CurrentUser currentUser
    );

    // 查詢 Comment 列表（依 currentUser 過濾 internal）
    List<CommentResponse> getCommentsByTicketId(
        UUID ticketId,
        CurrentUser currentUser
    );

    // 查詢單一 Comment（依 currentUser 驗證權限）
    CommentResponse getCommentById(
        UUID ticketId,
        UUID commentId,
        CurrentUser currentUser
    );
}
```

**設計原則**：
- 回傳 DTO，不回傳 Entity
- 使用 `UUID` 作為 ID 型別
- 所有方法傳入 `CurrentUser` 用於權限驗證
- 不提供刪除方法（永久保存）

### 6.2 Controller 權限標註

```java
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/comments")
@RequiredArgsConstructor
@Tag(name = "Comments", description = "留言管理 API")
public class CommentController {

    private final CommentService commentService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CommentResponse> create(
            @PathVariable UUID ticketId,
            @Valid @RequestBody CreateCommentRequest request,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        CommentResponse response = commentService.createComment(
            ticketId, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<CommentResponse>> findAll(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        List<CommentResponse> response = commentService.getCommentsByTicketId(
            ticketId, currentUser);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{commentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CommentResponse> getById(
            @PathVariable UUID ticketId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        CommentResponse response = commentService.getCommentById(
            ticketId, commentId, currentUser);
        return ResponseEntity.ok(response);
    }
}
```

**設計原則**：
- `@PreAuthorize("isAuthenticated()")` 僅驗證是否登入
- 詳細權限邏輯由 Service 層執行（因為涉及 Ticket 存取權限）

### 6.3 Request DTO 驗證

```java
public record CreateCommentRequest(
    @NotBlank(message = "Content is required")
    @Size(max = 10000, message = "Content must not exceed 10000 characters")
    String content,

    // 可選，預設 false
    // Service 層驗證：Customer 不能建立 internal note
    Boolean internal
) {}
```

### 6.4 Response DTO 格式

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
) {

    public static CommentResponse from(Comment comment) {
        return new CommentResponse(
            comment.getId(),
            comment.getTicketId(),
            comment.getAuthorId(),
            null,  // authorName 由 Service 層填充
            comment.getContent(),
            comment.isInternal(),
            comment.getCreatedAt(),
            comment.getUpdatedAt()
        );
    }
}
```

---

## 7. 錯誤處理原則

### 7.1 例外使用策略

| 例外類型 | HTTP Status | 使用時機 |
|----------|-------------|----------|
| `ResourceNotFoundException` | 404 | Ticket 或 Comment 不存在 |
| `ForbiddenOperationException` | 403 | 無權限操作或角色限制 |

### 7.2 權限驗證錯誤處理

```java
@Override
public CommentResponse createComment(
        UUID ticketId,
        CreateCommentRequest request,
        CurrentUser currentUser
) {
    Ticket ticket = ticketRepository.findById(ticketId)
        .orElseThrow(() -> new ResourceNotFoundException(
            "Ticket not found: " + ticketId));

    // 驗證 Ticket 存取權限
    validateTicketAccess(ticket, currentUser);

    // 驗證 internal note 權限
    boolean isInternal = Boolean.TRUE.equals(request.internal());
    if (isInternal && "CUSTOMER".equals(currentUser.role())) {
        throw new ForbiddenOperationException(
            "Customer cannot create internal notes");
    }

    // 建立 Comment
    Comment comment = new Comment();
    comment.setTicketId(ticketId);
    comment.setAuthorId(currentUser.userId());
    comment.setContent(request.content());
    comment.setInternal(isInternal);

    Comment saved = commentRepository.save(comment);
    return enrichResponse(saved, currentUser);
}
```

### 7.3 讀取權限過濾

```java
@Override
public List<CommentResponse> getCommentsByTicketId(
        UUID ticketId,
        CurrentUser currentUser
) {
    Ticket ticket = ticketRepository.findById(ticketId)
        .orElseThrow(() -> new ResourceNotFoundException(
            "Ticket not found: " + ticketId));

    // 驗證 Ticket 存取權限
    validateTicketAccess(ticket, currentUser);

    // 依角色查詢
    List<Comment> comments;
    if (isInternalViewable(currentUser)) {
        // ADMIN / AGENT：查詢全部
        comments = commentRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
    } else {
        // CUSTOMER：僅查詢 public
        comments = commentRepository.findByTicketIdAndInternalFalseOrderByCreatedAtAsc(ticketId);
    }

    return comments.stream()
        .map(c -> enrichResponse(c, currentUser))
        .toList();
}

private boolean isInternalViewable(CurrentUser currentUser) {
    return "ADMIN".equals(currentUser.role())
        || "AGENT".equals(currentUser.role());
}
```

### 7.4 錯誤訊息設計

| 情境 | 錯誤訊息 |
|------|----------|
| Ticket 不存在 | "Ticket not found: {ticketId}" |
| Comment 不存在 | "Comment not found: {commentId}" |
| 無權限存取 Ticket | "No permission to access this ticket" |
| Customer 嘗試建立 Internal Note | "Customer cannot create internal notes" |
| Customer 嘗試讀取 Internal Note | "No permission to view this comment" |

---

## 8. 技術決策摘要

| 決策 | 選擇 | 理由 |
|------|------|------|
| Entity 父類別 | `BaseEntity` | Comment 不可編輯，不需要 Version 欄位 |
| ID 型別 | UUID | 分散式安全，避免順序猜測 |
| 刪除 | 不提供 | 永久保存，確保稽核完整性 |
| 編輯 | 不提供 | 避免爭議，保持資料真實性 |
| 查詢方式 | JPA Repository | 簡單查詢，無需 Specification |
| 驗證 | JSR-380 (@Valid) | 標準化，宣告式 |
| 異常處理 | 統一 Exception Handler | 一致性 API 錯誤回應 |

---

## 9. 測試策略

| 測試類型 | 目標 | 覆蓋重點 |
|----------|------|----------|
| `CommentServiceTest` | Service 商業邏輯 | Mock Repository；權限驗證邏輯 |
| `CommentControllerTest` | API 端點 | HTTP 請求/回應驗證；角色權限 |

### 9.1 權限測試重點

| 測試情境 | 預期行為 |
|----------|----------|
| Customer 建立 Public Comment（自己的 Ticket） | ✅ 成功 |
| Customer 建立 Internal Note | ❌ ForbiddenOperationException |
| Customer 讀取 Public Comment | ✅ 成功 |
| Customer 讀取 Internal Note | ❌ ForbiddenOperationException |
| Customer 對他人的 Ticket 留言 | ❌ ForbiddenOperationException |
| Agent 建立 Public Comment（被指派的 Ticket） | ✅ 成功 |
| Agent 建立 Internal Note（被指派的 Ticket） | ✅ 成功 |
| Agent 對未指派的 Ticket 留言 | ❌ ForbiddenOperationException |
| Admin 建立 Comment（任何 Ticket） | ✅ 成功 |
| Admin 讀取所有 Comment | ✅ 成功 |

---

## 10. 未來擴展方向（Out of Scope）

- Comment 編輯功能
- Comment 刪除功能
- Comment 附件上傳
- Comment 通知系統
- Comment 稽核日誌
