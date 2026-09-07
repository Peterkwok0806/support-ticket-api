# Ticket 模組架構說明

## 1. 架構目標與限制

### 1.1 設計目標

| 目標 | 說明 |
|------|------|
| 領域驅動設計 | 以 Domain 為核心，確保商業邏輯內聚於 Ticket 模組 |
| 分層架構 | 嚴格遵守 Controller → Service → Repository 分層 |
| 狀態機封裝 | 狀態轉換邏輯集中於 `TicketStateMachine`，避免散佈於各層 |
| 權限控制 | 角色型權限控制（CUSTOMER / AGENT / ADMIN），防止未授權存取 |
| 可測試性 | 所有商業邏輯可透過單元測試驗證 |
| 一致性錯誤處理 | 統一使用既有 Exception 體系 |

### 1.2 約束條件

| 約束 | 說明 |
|------|------|
| 依賴方向 | 外部依賴（如 Repository、External Service）注入 Service，不可直接引入 Domain |
| 事務邊界 | Service 層為事務邊界，所有寫入操作皆在 `@Transactional` 內 |
| 唯讀優化 | 查詢操作標記 `@Transactional(readOnly = true)` |
| 封裝性 | Entity 欄位僅透過 Getter/Setter 存取，不暴露內部實作 |

---

## 2. 模組邊界與責任

### 2.1 模組結構

```
tickets/
├── domain/           # 領域模型（核心）
├── dto/              # 資料傳輸物件
├── repository/       # 資料存取
├── service/          # 商業邏輯
└── web/              # API 端點
```

### 2.2 責任歸屬

| 層 | 類別 | 責任 |
|----|------|------|
| **domain** | `Ticket` | 純領域實體，僅承載狀態與資料，不含商業邏輯 |
| **domain** | `TicketStateMachine` | 狀態轉換驗證，定義所有合法轉換規則 |
| **dto** | Request DTOs | 接收客戶端輸入，執行 JSR-380 驗證 |
| **dto** | Response DTOs | 格式化輸出，隔離 Entity 與 API 契約 |
| **repository** | `TicketRepository` | 資料庫存取，僅暴露查詢方法 |
| **service** | `TicketService` | 商業邏輯編排，協調 Domain、DTO、Repository；**執行權限驗證** |
| **web** | Controllers | HTTP 協定處理，請求轉發；**使用 @PreAuthorize 標註角色權限** |

### 2.3 不應出現的職責

| 位置 | 不應包含 |
|------|----------|
| Controller | 商業邏輯、資料庫查詢、狀態驗證 |
| Repository | 商業邏輯、業務規則 |
| Entity | 跨領域邏輯、HTTP 處理 |
| DTO | 商業邏輯、資料庫存取 |

---

## 3. 依賴方向

### 3.1 依賴關係圖

```
┌─────────────────────────────────────────────────────────┐
│                      web/                                │
│                  (Controllers)                           │
│                                                          │
│              depends on: TicketService                    │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│                      service/                            │
│              (TicketServiceImpl)                         │
│                                                          │
│  depends on: Ticket, TicketStateMachine, Repository      │
└──────────────────────┬──────────────────────────────────┘
                       │
        ┌──────────────┴──────────────┐
        ▼                              ▼
┌───────────────────┐      ┌───────────────────┐
│     domain/       │      │    repository/    │
│  Ticket (Entity)  │      │ TicketRepository  │
│ TicketStateMachine│      └───────────────────┘
└───────────────────┘
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

---

## 4. 核心資料模型的責任歸屬

### 4.1 Ticket Entity

```java
@Entity
public class Ticket extends VersionedEntity {
    // 資料欄位（title, status, priority, ...）
    // Getter/Setter（由 Lombok 生成）
    // 不含商業邏輯
}
```

**責任**：
- 承載 Ticket 的所有屬性
- 提供資料讀寫接口
- 由 JPA 管理生命週期

**不含**：
- 狀態轉換驗證邏輯
- 時間計算邏輯
- 跨領域操作

### 4.2 TicketStateMachine

```java
public class TicketStateMachine {
    // 狀態轉換規則（EnumMap 或 Map）
    // validateTransition(from, to): boolean
    // getAllowedTransitions(from): Set<TicketStatus>
}
```

**責任**：
- 定義所有合法狀態轉換
- 驗證轉換請求是否有效
- 提供允許轉換查詢

**不含**：
- 直接修改 Ticket 狀態
- 時間戳更新邏輯
- 資料庫操作

### 4.3 TicketService

```java
public class TicketServiceImpl implements TicketService {
    // 協調 Ticket + TicketStateMachine + Repository
    // 執行商業邏輯
    // 管理事務邊界
}
```

**責任**：
- 編排商業流程（建立 → 計算 SLA → 儲存）
- 呼叫狀態機驗證
- 管理事務（@Transactional）
- 處理副作用（更新時間戳、重新計算 SLA）

---

## 5. 主要資料流程

### 5.1 建立 Ticket

```
Client POST /api/v1/tickets (JWT: CUSTOMER | ADMIN)
         │
         ▼
┌─────────────────────────────────┐
│  @PreAuthorize 角色驗證         │ ◄── 僅 CUSTOMER, ADMIN 可建立
│  TicketController.create()      │
└───────────────┬─────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│     TicketServiceImpl          │
│  1. validate category exists    │
│  2. calculate sla deadline      │ ◄── 使用 SlaCalculator
│  3. set status = OPEN           │
│  4. set createdBy = currentUser │
│  5. save via repository         │
└───────────────┬─────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│     TicketRepository           │
│  INSERT INTO tickets (...)     │
└─────────────────────────────────┘
```

### 5.2 變更狀態

```
Client PATCH /api/v1/tickets/{id}/status (JWT: AGENT | ADMIN)
         │
         ▼
┌─────────────────────────────────┐
│  @PreAuthorize 角色驗證         │ ◄── 僅 AGENT, ADMIN 可變更狀態
│  TicketController.updateStatus()│
└───────────────┬─────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│     TicketServiceImpl          │
│  1. find ticket by id          │
│  2. 權限驗證：                   │ ◄── ADMIN 或 (AGENT 且 assignedTo = currentUser)
│  3. stateMachine.validate()     │ ◄── 若失敗拋出 ForbiddenOperationException
│  4. ticket.setStatus(new)      │
│  5. handle side effects        │ ◄── resolvedAt, closedAt, firstResponseAt
│  6. save via repository        │
└─────────────────────────────────┘
```

### 5.3 查詢 Ticket

```
Client GET /api/v1/tickets?status=OPEN (JWT: ALL)
         │
         ▼
┌─────────────────────────────────┐
│  TicketController.findAll()    │
│  (依賴 @PreAuthorize 驗證角色)  │
└───────────────┬─────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│     TicketServiceImpl          │
│  1. 根據角色過濾查詢範圍：       │
│     - ADMIN: 不限制             │
│     - AGENT: assignedTo=me      │
│     - CUSTOMER: createdBy=me    │
│  2. (readOnly transaction)     │
│  3. call repository with spec   │
└───────────────┬─────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│  TicketRepository + Spec       │
│  SELECT ... WHERE ...           │
└─────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│  map Entity → Response DTO     │
└─────────────────────────────────┘
```

---

## 6. 介面設計原則

### 6.1 Service 介面（契約）

```java
public interface TicketService {

    // 建立（傳入建立者 ID，由 Controller 注入 currentUser）
    TicketResponse createTicket(CreateTicketRequest request, UUID createdBy);

    // 查詢（傳入 currentUser 進行權限過濾）
    TicketResponse getTicketById(UUID id, CurrentUser currentUser);
    PageResponse<TicketSummaryResponse> getTickets(
        TicketFilterRequest filter,
        Pageable pageable,
        CurrentUser currentUser
    );

    // 更新（僅 ADMIN）
    TicketResponse updateTicket(UUID id, UpdateTicketRequest request);

    // 狀態變更（傳入 currentUser 驗證指派）
    TicketResponse updateStatus(UUID id, TicketStatusUpdateRequest request, CurrentUser currentUser);

    // 指派（僅 ADMIN）
    TicketResponse assignTicket(UUID id, TicketAssignRequest request);

    // 刪除（僅 ADMIN）
    void deleteTicket(UUID id);
}
```

**設計原則**：
- 回傳 DTO，不回傳 Entity
- 使用 `UUID` 作為 ID 型別
- 分頁查詢使用 `Pageable` + `PageResponse`
- 查詢/變更方法傳入 `CurrentUser` 用於權限驗證

### 6.2 Controller 權限標註

```java
@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class TicketController {

    // === CUSTOMER & ADMIN ===

    @PostMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    public ResponseEntity<TicketResponse> create(...) { }

    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> getById(...) {
        // 權限檢查由 Service 層執行
        // - ADMIN：可讀取所有
        // - CUSTOMER：只能讀取自己建立的
        // - AGENT：只能讀取被指派給自己的
    }

    @GetMapping
    public ResponseEntity<PageResponse<TicketSummaryResponse>> findAll(...) {
        // 權限檢查由 Service 層執行
        // 自動過濾結果範圍
    }

    // === ADMIN ONLY ===

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TicketResponse> update(...) { }

    // === AGENT & ADMIN ===

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('AGENT', 'ADMIN')")
    public ResponseEntity<TicketResponse> updateStatus(...) {
        // Service 層額外驗證 assignedTo
    }

    @PatchMapping("/{id}/assign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TicketResponse> assign(...) { }
}
```

### 6.3 Request DTO 驗證

```java
public record CreateTicketRequest(
    @NotBlank(message = "Title is required")
    @Size(max = 200, message = "Title must not exceed 200 characters")
    String title,

    String description,

    @NotNull(message = "Category is required")
    UUID categoryId,

    TicketPriority priority  // 可選，有預設值
) {}
```

### 6.4 Response DTO 格式

```java
public record TicketResponse(
    UUID id,
    String title,
    String description,
    TicketStatus status,
    TicketPriority priority,
    CategorySummaryResponse category,  // 關聯物件
    String createdByName,               // 展示用名稱
    String assignedToName,
    Instant slaDeadline,
    Instant resolvedAt,
    Instant closedAt,
    Instant createdAt,
    Instant updatedAt
) {}
```

---

## 7. 錯誤處理原則

### 7.1 例外使用策略

| 例外類型 | 使用時機 | HTTP Status |
|----------|----------|-------------|
| `ResourceNotFoundException` | 資源不存在 | 404 |
| `BusinessRuleException` | 商業規則違反（驗證失敗） | 400 |
| `ForbiddenOperationException` | 無權限操作或狀態機不允許的轉換 | 409 |
| `ConflictException` | 資料衝突（重複等） | 409 |
| `OptimisticLockException` | 併發更新衝突（自動處理） | 409 |

### 7.2 狀態機 + 權限錯誤處理

```java
@Override
public TicketResponse updateStatus(UUID id, TicketStatusUpdateRequest request, CurrentUser currentUser) {
    Ticket ticket = findTicketById(id);

    // 權限驗證：ADMIN 或 (AGENT 且 assignedTo = currentUser)
    if (!canChangeStatus(ticket, currentUser)) {
        throw new ForbiddenOperationException("No permission to change ticket status");
    }

    // 狀態機驗證
    if (!stateMachine.canTransition(ticket.getStatus(), request.status())) {
        throw new ForbiddenOperationException(
            String.format("Cannot transition from %s to %s",
                ticket.getStatus(), request.status())
        );
    }

    // 執行轉換
    ticket.setStatus(request.status());
    handleStatusSideEffects(ticket, request.status());

    return enrichResponse(repository.save(ticket));
}

private boolean canChangeStatus(Ticket ticket, CurrentUser currentUser) {
    return switch (Role.valueOf(currentUser.role())) {
        case ADMIN -> true;
        case AGENT -> ticket.getAssignedTo() != null
                && ticket.getAssignedTo().equals(currentUser.userId());
        case CUSTOMER -> false;
    };
}
```

### 7.3 Service 層權限過濾

```java
@Override
public PageResponse<TicketSummaryResponse> getTickets(
        TicketFilterRequest filter,
        Pageable pageable,
        CurrentUser currentUser
) {
    // 根據角色調整查詢過濾條件
    TicketFilterRequest adjustedFilter = applyPermissionFilter(filter, currentUser);

    Page<Ticket> page = ticketRepository.findAll(
        TicketSpecification.withFilters(
            adjustedFilter.statuses(),
            adjustedFilter.priority(),
            adjustedFilter.categoryId(),
            adjustedFilter.assignedTo(),
            adjustedFilter.createdBy(),
            adjustedFilter.keyword()
        ),
        pageable
    );

    return PageResponse.from(page, TicketSummaryResponse::from);
}

private TicketFilterRequest applyPermissionFilter(
        TicketFilterRequest filter,
        CurrentUser currentUser
) {
    return switch (Role.valueOf(currentUser.role())) {
        case ADMIN -> filter; // 不限制
        case AGENT -> new TicketFilterRequest(
            filter.statuses(),
            filter.priority(),
            filter.categoryId(),
            currentUser.userId(),  // 強制過濾 assignedTo = currentUser
            null,
            filter.keyword()
        );
        case CUSTOMER -> new TicketFilterRequest(
            filter.statuses(),
            filter.priority(),
            filter.categoryId(),
            null,
            currentUser.userId(),  // 強制過濾 createdBy = currentUser
            filter.keyword()
        );
    };
}
```

### 7.4 全域 Exception Handler（擴展）

```java
// ApiExceptionHandler 中新增
@ExceptionHandler(TicketException.class)
public ResponseEntity<ErrorResponse> handleTicketException(
    TicketException ex, HttpServletRequest request
) {
    return buildResponse(
        HttpStatus.BAD_REQUEST,
        "TICKET_ERROR",
        ex.getMessage(),
        request.getRequestURI(),
        List.of()
    );
}
```

---

## 8. 跨模組互動

### 8.1 Ticket 依賴 Category

```
tickets.service ──► categories.repository
```

建立或更新 Ticket 時需驗證 Category 存在：

```java
// TicketServiceImpl
private void validateCategoryExists(UUID categoryId) {
    if (!categoryRepository.existsById(categoryId)) {
        throw new BusinessRuleException("Category not found: " + categoryId);
    }
}
```

### 8.2 Ticket 依賴 User

```
tickets.service ──► users.repository
```

- 建立者（createdBy）、指名派對象（assignedTo）需驗證用戶存在
- 使用既有 `UserRepository`

### 8.3 Ticket 依賴 SlaCalculator

```
tickets.service ──► categories.service.SlaCalculator
```

建立 Ticket 或優先級變更時計算 SLA deadline：

```java
// TicketServiceImpl
private final SlaCalculator slaCalculator;

private void calculateAndSetSlaDeadline(Ticket ticket, Category category) {
    Instant slaDeadline = slaCalculator.calculateSlaDueAt(
        category,
        ticket.getPriority(),
        ticket.getCreatedAt()
    );
    ticket.setSlaDeadline(slaDeadline);
}
```

---

## 9. 技術決策摘要

| 決策 | 選擇 | 理由 |
|------|------|------|
| 狀態機實作 | 自定義 StateMachine 類 | 簡單直觀，符合 DDD |
| ID 型別 | UUID | 分散式安全，避免順序猜測 |
| 樂觀鎖 | @Version | 防止併發更新衝突 |
| 查詢方式 | JPA Specification | 支援動態多條件查詢 |
| 驗證 | JSR-380 (@Valid) | 標準化，宣告式 |
| 異常處理 | 統一 Exception Handler | 一致性 API 錯誤回應 |

---

## 10. 測試策略

| 測試類型 | 目標 | 覆蓋重點 |
|----------|------|----------|
| `TicketStateMachineTest` | 狀態機邏輯 | 所有轉換規則、有效/無效案例 |
| `TicketServiceTest` | Service 商業邏輯 | Mock Repository + StateMachine；**權限驗證邏輯** |
| `TicketControllerTest` | API 端點 | HTTP 請求/回應驗證；**@PreAuthorize 角色權限** |

### 10.1 權限測試重點

| 測試情境 | 預期行為 |
|----------|----------|
| Customer 嘗試建立 Ticket | ✅ 成功 |
| Agent 嘗試建立 Ticket | ❌ 403 Forbidden |
| Agent 嘗試變更非指派給自己的 Ticket 狀態 | ❌ ForbiddenOperationException |
| Customer 嘗試查詢他人的 Ticket | ❌ ForbiddenOperationException |
| Admin 可操作所有 Ticket | ✅ 成功 |

---

## 11. 未來擴展方向（Out of Scope）

- Comment（評論）功能
- Attachment（附件）上傳
- Notification（通知）系統整合
- SLA 逾時提醒（Scheduler）
- 多語系支援
