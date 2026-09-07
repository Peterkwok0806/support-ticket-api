# Category Features Architecture

## 1. 架構目標與限制

### 1.1 設計目標

| 目標 | 說明 |
|------|------|
| **職責分離** | 嚴格遵循分層架構，Controller → Service → Repository |
| **可測試性** | Service 層邏輯需可透過 Mockito 隔離測試 |
| **一致性** | 遵循現有 `common` 模組的設計模式與命名慣例 |
| **安全性** | 所有 Admin API 需驗證 ADMIN 角色，SLA 設定僅 Admin 可修改 |

### 1.2 技術約束

| 約束 | 依據 |
|------|------|
| Framework | Java 21 + Spring Boot 4.1.1 |
| 架構模式 | Modular Monolith（package-by-feature） |
| ORM | Spring Data JPA + Hibernate |
| 資料庫 | PostgreSQL + Flyway |
| 認證 | JWT（由 `auth` 模組提供） |
| 驗證 | Bean Validation |

### 1.3 禁止事項

- 不得在 Controller 層撰寫商業邏輯
- 不得直接回傳 Entity 作為 HTTP Response
- 不得在 Response 中包含敏感內部資訊
- 不得使用 String 拼接 SQL（需使用 JPQL 或 Specification）
- 不得在非 Transactional 方法中進行多表寫入操作
- 不得實體刪除 Category（Soft Delete Only）

---

## 2. 模組邊界與責任

### 2.1 模組位置

```
src/main/java/com/pk/support_ticket_api/categories/
├── domain/                          # 領域模型
│   ├── Category.java              # Category Entity
│   └── CategorySpecification.java # Spring Data Specification
├── repository/                      # 資料存取
│   └── CategoryRepository.java    # JPA Repository
├── service/                         # 商業邏輯
│   ├── CategoryService.java       # Service Interface
│   ├── CategoryServiceImpl.java   # Service Implementation
│   └── SlaCalculator.java         # SLA 到期時間計算
├── dto/                            # 資料傳輸物件
│   ├── CreateCategoryRequest.java
│   ├── UpdateCategoryRequest.java
│   ├── CategoryResponse.java
│   └── CategorySummaryResponse.java
├── exception/                      # 模組專屬例外
│   └── CategoryException.java
└── web/                           # API 端點
    ├── CategoryAdminController.java  # Admin API
    └── CategoryController.java        # Public API
```

### 2.2 責任矩陣

| 層級 | 元件 | 責任 |
|------|------|------|
| **Domain** | `Category.java` | 實體映射、欄位約束、SLA 時數欄位 |
| **Domain** | `CategorySpecification.java` | 動態查詢條件組合 |
| **Domain** | `SlaCalculator.java` | 根據 Category + Priority 計算 SLA 到期時間 |
| **Repository** | `CategoryRepository.java` | 資料庫存取介面 |
| **Service** | `CategoryService.java` | 商業邏輯介面定義 |
| **Service** | `CategoryServiceImpl.java` | CRUD、商業規則驗證 |
| **DTO** | `CreateCategoryRequest` | 建立分類請求驗證 |
| **DTO** | `UpdateCategoryRequest` | 更新分類請求驗證 |
| **DTO** | `CategoryResponse` | 分類完整資料回應（含 SLA） |
| **DTO** | `CategorySummaryResponse` | 分類摘要回應（僅 id、name） |
| **Controller** | `CategoryAdminController.java` | Admin HTTP 協商、路由 |
| **Controller** | `CategoryController.java` | Public HTTP 協商、路由 |

---

## 3. 依賴方向

### 3.1 依賴規則

```
┌─────────────────────────────────────────────────────────┐
│                     Controller                          │
│              (依賴 Service、DTO、CurrentUser)           │
└─────────────────────────┬───────────────────────────────┘
                          │ 依賴
┌─────────────────────────▼───────────────────────────────┐
│                     Service                             │
│     (依賴 Repository、Entity、Exception、SlaCalculator)  │
└─────────────────────────┬───────────────────────────────┘
                          │ 依賴
┌─────────────────────────▼───────────────────────────────┐
│                   Repository                            │
│              (依賴 Entity、JPA EntityManager)            │
└─────────────────────────────────────────────────────────┘
```

### 3.2 外部依賴

| 依賴 | 位置 | 用途 |
|------|------|------|
| `BaseEntity` | `common/domain/BaseEntity.java` | 提供 id, createdAt, updatedAt |
| `TicketPriority` | `common/domain/enums/TicketPriority.java` | Priority 枚舉 |
| `CurrentUser` | `common/security/CurrentUser.java` | 當前登入者資訊 |
| `PageResponse` | `common/response/PageResponse.java` | 分頁響應格式 |
| `ResourceNotFoundException` | `common/exception/ResourceNotFoundException.java` | 資源不存在例外 |
| `ConflictException` | `common/exception/ConflictException.java` | 衝突例外（名稱重複） |
| `BusinessRuleException` | `common/exception/BusinessRuleException.java` | 商業規則違反例外 |

### 3.3 被依賴關係

| 元件 | 被誰依賴 |
|------|----------|
| `Category` Entity | Ticket（category_id 外鍵） |
| `SlaCalculator` | TicketService（建立 Ticket 時計算 SLA） |
| `CategoryRepository` | TicketService（驗證 Category 啟用狀態） |

---

## 4. 核心資料模型的責任歸屬

### 4.1 Category Entity

```java
@Entity
@Table(name = "categories")
public class Category extends BaseEntity {
    
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "sla_hours_low", nullable = false)
    private Integer slaHoursLow = 72;
    
    @Column(name = "sla_hours_medium", nullable = false)
    private Integer slaHoursMedium = 48;
    
    @Column(name = "sla_hours_high", nullable = false)
    private Integer slaHoursHigh = 24;
    
    @Column(name = "sla_hours_urgent", nullable = false)
    private Integer slaHoursUrgent = 4;
    
    @Column(name = "is_active", nullable = false)
    private Boolean active = true;
}
```

### 4.2 欄位職責歸屬

| 欄位 | 產生方式 | 驗證責任 | 業務約束 |
|------|----------|----------|----------|
| `id` | Database (UUID) | - | 唯讀，不可修改 |
| `name` | 建立/更新時提供 | Service 層唯一性檢查 | 需建立 Unique Index，長度 1-100 |
| `description` | 建立/更新時提供 | Bean Validation（長度 0-500） | 可為 NULL |
| `slaHoursLow` | 建立/更新時提供 | Bean Validation（範圍 1-720） | 預設值 72 |
| `slaHoursMedium` | 建立/更新時提供 | Bean Validation（範圍 1-720） | 預設值 48 |
| `slaHoursHigh` | 建立/更新時提供 | Bean Validation（範圍 1-720） | 預設值 24 |
| `slaHoursUrgent` | 建立/更新時提供 | Bean Validation（範圍 1-720） | 預設值 4 |
| `active` | 預設 true | - | 由 activate/deactivate 操作修改 |
| `createdAt` | JPA @CreatedDate | - | 唯讀 |
| `updatedAt` | JPA @LastModifiedDate | - | 自動更新 |

### 4.3 SLA 時數對照表

| Priority | 欄位 | 預設值 | 範圍 |
|----------|------|--------|------|
| LOW | `slaHoursLow` | 72 | 1-720 小時 |
| MEDIUM | `slaHoursMedium` | 48 | 1-720 小時 |
| HIGH | `slaHoursHigh` | 24 | 1-720 小時 |
| URGENT | `slaHoursUrgent` | 4 | 1-720 小時 |

---

## 5. 主要資料流程

### 5.1 建立分類流程

```
Client POST /api/admin/categories
        │
        ▼
┌─────────────────────────────────────────────────┐
│ CategoryAdminController                           │
│  - @PreAuthorize("hasRole('ADMIN')")           │
│  - @Valid CreateCategoryRequest                  │
│  - 呼叫 categoryService.createCategory(request)  │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│ CategoryServiceImpl.createCategory()             │
│  1. 驗證 name 唯一性（repository.existsByName） │
│  2. 若衝突 → 拋出 ConflictException             │
│  3. 驗證 SLA 時數範圍（1-720）                  │
│  4. 建立 Category Entity                        │
│  5. repository.save(category)                   │
│  6. 回傳 CategoryResponse                        │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
Response: 201 Created + CategoryResponse
```

### 5.2 停用分類流程

```
Client PATCH /api/admin/categories/{id}/deactivate
        │
        ▼
┌─────────────────────────────────────────────────┐
│ CategoryAdminController                           │
│  - 驗證 ADMIN 角色                               │
│  - 呼叫 categoryService.deactivate(id)           │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│ CategoryServiceImpl.deactivate()                 │
│  1. 查詢分類（findById）                        │
│  2. 若不存在 → ResourceNotFoundException        │
│  3. 已是停用狀態 → BusinessRuleException        │
│  4. 更新 active = false                         │
│  5. repository.save(category)                   │
│  6. 回傳 CategoryResponse                        │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
Response: 200 OK + CategoryResponse
```

### 5.3 公開列表查詢流程

```
Client GET /api/categories
        │
        ▼
┌─────────────────────────────────────────────────┐
│ CategoryController                               │
│  - 不需驗證角色（公開端點）                      │
│  - 呼叫 categoryService.findActiveCategories()   │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│ CategoryServiceImpl.findActiveCategories()       │
│  1. repository.findByActiveTrue(pageable)      │
│  2. Page<Category> → PageResponse               │
│     <CategorySummaryResponse>                    │
│  3. 回傳分頁結果（僅 id、name）                 │
└─────────────────────────────────────────────────┘
```

### 5.4 SLA 計算流程（由 TicketService 呼叫）

```
TicketService 建立 Ticket 時
        │
        ▼
┌─────────────────────────────────────────────────┐
│ SlaCalculator.calculateSlaDueAt()                 │
│  1. 接收 Category、Priority、createdAt          │
│  2. 根據 Priority 取得對應 SLA 小時數            │
│     - LOW    → category.getSlaHoursLow()         │
│     - MEDIUM → category.getSlaHoursMedium()     │
│     - HIGH   → category.getSlaHoursHigh()       │
│     - URGENT → category.getSlaHoursUrgent()     │
│  3. 計算到期時間：createdAt + slaHours 小時      │
│  4. 回傳 Instant（slaDueAt）                     │
└─────────────────────────────────────────────────┘
```

### 5.5 Specification 條件組合

```java
public class CategorySpecification {
    
    public static Specification<Category> withFilters(
            Boolean active,
            String keyword
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword.toLowerCase() + "%";
                predicates.add(cb.like(
                    cb.lower(root.get("name")), pattern));
            }
            
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
```

---

## 6. 介面／錯誤處理原則

### 6.1 API 端點定義

| 方法 | 路徑 | 說明 | 所需角色 |
|------|------|------|----------|
| `GET` | `/api/admin/categories` | 分頁查詢分類 | ADMIN |
| `GET` | `/api/admin/categories/{id}` | 取得單一分類 | ADMIN |
| `POST` | `/api/admin/categories` | 建立新分類 | ADMIN |
| `PUT` | `/api/admin/categories/{id}` | 更新分類（含 SLA 設定） | ADMIN |
| `PATCH` | `/api/admin/categories/{id}/deactivate` | 停用分類 | ADMIN |
| `PATCH` | `/api/admin/categories/{id}/activate` | 啟用分類 | ADMIN |
| `GET` | `/api/categories` | 取得啟用中的分類 | ALL |

### 6.2 錯誤響應格式

所有錯誤使用 `common/exception/ApiExceptionHandler` 統一處理：

| HTTP Status | 例外類型 | 錯誤格式 |
|-------------|----------|----------|
| 400 | ValidationException | `{"errors": [...], "timestamp": "...", "traceId": "..."}` |
| 401 | AuthenticationException | `{"message": "Unauthorized", "traceId": "..."}` |
| 403 | AccessDeniedException | `{"message": "Forbidden", "traceId": "..."}` |
| 404 | ResourceNotFoundException | `{"message": "Category not found", "traceId": "..."}` |
| 409 | ConflictException | `{"message": "Category name already exists", "traceId": "..."}` |
| 422 | BusinessRuleException | `{"message": "Category is already deactivated", "traceId": "..."}` |

### 6.3 例外處理責任

| 例外 | 拋出位置 | 訊息格式 |
|------|----------|----------|
| `ResourceNotFoundException` | Service | `"Category not found with id: {id}"` |
| `ConflictException` | Service | `"Category name already exists: {name}"` |
| `BusinessRuleException` | Service | `"Category is already deactivated"` |
| `ValidationException` | DTO (Bean Validation) | 欄位層級錯誤 |

### 6.4 Validation 規則

**CreateCategoryRequest:**
```java
public record CreateCategoryRequest(
    @NotBlank(message = "名稱為必填")
    @Size(min = 1, max = 100, message = "名稱長度需 1-100 字元")
    String name,
    
    @Size(max = 500, message = "描述長度最大 500 字元")
    String description,
    
    @NotNull(message = "SLA Low 小時數為必填")
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursLow,
    
    @NotNull(message = "SLA Medium 小時數為必填")
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursMedium,
    
    @NotNull(message = "SLA High 小時數為必填")
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursHigh,
    
    @NotNull(message = "SLA Urgent 小時數為必填")
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursUrgent
) {}
```

**UpdateCategoryRequest:**
```java
public record UpdateCategoryRequest(
    @Size(min = 1, max = 100, message = "名稱長度需 1-100 字元")
    String name,
    
    @Size(max = 500, message = "描述長度最大 500 字元")
    String description,
    
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursLow,
    
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursMedium,
    
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursHigh,
    
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursUrgent
) {}
```

---

## 7. 測試策略

### 7.1 Unit Test — CategoryService

**測試檔案**: `src/test/java/com/pk/support_ticket_api/categories/service/CategoryServiceTest.java`

| 測試案例 | 測試內容 |
|----------|----------|
| `createCategory_Success` | 正常建立分類，回傳正確 Response |
| `createCategory_DuplicateName` | 名稱重複時拋出 ConflictException |
| `createCategory_SlaHoursOutOfRange` | SLA 小時數超出範圍時拋出 ValidationException |
| `updateCategory_Success` | 正常更新，回傳更新後資料 |
| `updateCategory_NameToExisting` | 更新為已存在的名稱時拋出 ConflictException |
| `updateCategory_SlaHours` | 更新 SLA 設定正確套用 |
| `deactivate_Success` | 正常停用，active 變為 false |
| `deactivate_AlreadyDeactivated` | 已是停用狀態拋出 BusinessRuleException |
| `activate_Success` | 正常啟用，active 變為 true |
| `activate_AlreadyActive` | 已是啟用狀態拋出 BusinessRuleException |
| `findAll_WithFilters` | 分頁查詢正確套用 Specification 條件 |
| `findActiveCategories_OnlyActive` | 公開列表僅回傳 active=true 的分類 |

### 7.2 Unit Test — SlaCalculator

**測試檔案**: `src/test/java/com/pk/support_ticket_api/categories/service/SlaCalculatorTest.java`

| 測試案例 | 測試內容 |
|----------|----------|
| `calculateSlaDueAt_Low` | LOW Priority 正確使用 slaHoursLow |
| `calculateSlaDueAt_Medium` | MEDIUM Priority 正確使用 slaHoursMedium |
| `calculateSlaDueAt_High` | HIGH Priority 正確使用 slaHoursHigh |
| `calculateSlaDueAt_Urgent` | URGENT Priority 正確使用 slaHoursUrgent |
| `calculateSlaDueAt_CorrectTimeCalculation` | 時間計算正確（72 小時 = 3 天） |

### 7.3 測試資料 Seed

使用 Flyway `V4__seed_categories.sql` 建立測試資料：

```sql
INSERT INTO categories (id, name, description, sla_hours_low, sla_hours_medium, sla_hours_high, sla_hours_urgent, is_active, created_at, updated_at)
VALUES
  ('a0000000-0000-0000-0000-000000000001', '技術問題',
   '軟硬體技術相關問題', 72, 48, 24, 4, TRUE, NOW(), NOW()),
  
  ('a0000000-0000-0000-0000-000000000002', '帳務相關',
   '帳單、付款、發票等問題', 96, 72, 48, 8, TRUE, NOW(), NOW()),
  
  ('a0000000-0000-0000-0000-000000000003', '停用的分類',
   '已停用的測試分類', 72, 48, 24, 4, FALSE, NOW(), NOW());
```

---

## 8. 禁止事項與待確認決策

### 8.1 禁止事項（Must Not）

| 項目 | 原因 | 替代方案 |
|------|------|----------|
| 不得實體刪除 Category | 保護歷史資料完整性 | Soft Delete（設定 active = false） |
| 不得在公開端點暴露 SLA 設定 | SLA 屬於內部管理資訊 | 使用 CategorySummaryResponse |
| 不得使用 String concatenation 組 SQL | SQL Injection | 使用 JPQL/Specification |
| 不得繞過 Bean Validation | 資料一致性 | 所有輸入皆需驗證 |
| 不得在非 Transactional 方法中進行寫入 | 資料一致性 | 設計合理的 Transaction 邊界 |

### 8.2 假設條件

| 項目 | 預設值 |
|------|--------|
| SLA 時數最小值 | 1 小時 |
| SLA 時數最大值 | 720 小時（30 天） |
| 刪除邏輯 | Soft Delete（設定 active = false） |
| 預設 SLA 值 | Low: 72h, Medium: 48h, High: 24h, Urgent: 4h |
| SLA 計算方式 | 日曆時間計算，不排除非工作時間 |

---

## 9. 附錄

### 9.1 檔案清單

| 檔案 | 位置 | 說明 |
|------|------|------|
| BaseEntity | `common/domain/BaseEntity.java` | 實體基底類別 |
| TicketPriority | `common/domain/enums/TicketPriority.java` | Priority 枚舉 |
| CurrentUser | `common/security/CurrentUser.java` | 當前用戶資訊 |
| PageResponse | `common/response/PageResponse.java` | 分頁響應 |
| ApiExceptionHandler | `common/exception/ApiExceptionHandler.java` | 統一例外處理 |
| Category | `categories/domain/Category.java` | 分類實體 |
| CategoryRepository | `categories/repository/CategoryRepository.java` | 資料存取 |
| CategoryService | `categories/service/CategoryService.java` | 服務介面 |
| CategoryServiceImpl | `categories/service/CategoryServiceImpl.java` | 服務實作 |
| SlaCalculator | `categories/service/SlaCalculator.java` | SLA 計算 |
| CategoryAdminController | `categories/web/CategoryAdminController.java` | Admin API |
| CategoryController | `categories/web/CategoryController.java` | Public API |
| V4__seed_categories | `resources/db/migration/V4__seed_categories.sql` | 測試資料 |

### 9.2 與 Ticket 模組的整合點

| 整合點 | 方向 | 說明 |
|--------|------|------|
| `SlaCalculator` | Category → Ticket | TicketService 建立時計算 SLA |
| `CategoryRepository` | Category → Ticket | TicketService 驗證 Category 啟用狀態 |

### 9.3 參考文件

| 文件 | 位置 |
|------|------|
| 專案概覽 | `doc/overview.md` |
| 分類需求 | `doc/requirements/categories.md` |
| 使用者架構 | `doc/architecture/user.md` |

---

*文件版本: 1.0*
*建立日期: 2026-09-04*
*最後更新: 2026-09-04*
