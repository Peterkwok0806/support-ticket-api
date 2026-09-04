# Category 與 SLA 規則設定 - 功能需求規格書

## 1. 模組概述

### 1.1 目的

建立 **Category 模組**，支援工單分類管理，並將 SLA（Service Level Agreement）時數直接內嵌於 Category 中。每個 Category 可定義不同 Priority 對應的 SLA 回應時限，Ticket 建立時自動根據 Category + Priority 計算 SLA 到期時間。

### 1.2 技術上下文

- **Framework**: Java 21 + Spring Boot 4.1.1
- **架構模式**: Modular Monolith（package-by-feature）
- **ORM**: Spring Data JPA + Hibernate
- **資料庫**: PostgreSQL + Flyway
- **驗證**: Bean Validation

---

## 2. 資料模型

### 2.1 Category Entity

| 欄位 | 型別 | 約束 | 說明 |
|------|------|------|------|
| `id` | UUID | PK, auto-generated | 主鍵 |
| `name` | String | unique, not null, max 100 | 分類名稱 |
| `description` | String | nullable, max 500 | 分類描述 |
| `slaHoursLow` | Integer | not null, default 72, range 1-720 | Low Priority SLA 小時數 |
| `slaHoursMedium` | Integer | not null, default 48, range 1-720 | Medium Priority SLA 小時數 |
| `slaHoursHigh` | Integer | not null, default 24, range 1-720 | High Priority SLA 小時數 |
| `slaHoursUrgent` | Integer | not null, default 4, range 1-720 | Urgent Priority SLA 小時數 |
| `active` | Boolean | not null, default true | 是否啟用 |
| `createdAt` | Instant | not null, auto | 建立時間 |
| `updatedAt` | Instant | not null, auto | 更新時間 |

### 2.2 資料庫 Schema 變更

新增 Migration 檔案 `V3__modify_categories_add_sla.sql`：

```sql
-- V3__modify_categories_add_sla.sql
-- 為 categories 表格新增 SLA 時數欄位

ALTER TABLE categories
    ADD COLUMN IF NOT EXISTS sla_hours_low INTEGER NOT NULL DEFAULT 72
        CONSTRAINT chk_sla_hours_low CHECK (sla_hours_low BETWEEN 1 AND 720),
    ADD COLUMN IF NOT EXISTS sla_hours_medium INTEGER NOT NULL DEFAULT 48
        CONSTRAINT chk_sla_hours_medium CHECK (sla_hours_medium BETWEEN 1 AND 720),
    ADD COLUMN IF NOT EXISTS sla_hours_high INTEGER NOT NULL DEFAULT 24
        CONSTRAINT chk_sla_hours_high CHECK (sla_hours_high BETWEEN 1 AND 720),
    ADD COLUMN IF NOT EXISTS sla_hours_urgent INTEGER NOT NULL DEFAULT 4
        CONSTRAINT chk_sla_hours_urgent CHECK (sla_hours_urgent BETWEEN 1 AND 720);
```

> ⚠️ **Migration 策略**：不可修改已執行的 Migration。採用新增 Migration 策略。

### 2.3 SLA 時限對照表（預設值）

| Priority | 預設 SLA 小時數 | 預設等於 |
|----------|----------------|----------|
| LOW | 72 | 3 天 |
| MEDIUM | 48 | 2 天 |
| HIGH | 24 | 1 天 |
| URGENT | 4 | 4 小時 |

---

## 3. 商業規則

### 3.1 SLA 時數限制

- **最小值**：1 小時
- **最大值**：720 小時（30 天）
- **驗證時機**：建立或更新 Category 時

### 3.2 Category 停用規則

| 行為 | 說明 |
|------|------|
| 現有 Ticket | 可正常瀏覽、編輯，無影響 |
| 新建 Ticket | 停用的 Category 不可選擇 |
| API 行為 | `GET /api/categories` 預設僅回傳 `active=true` 的項目 |

### 3.3 Category 刪除規則

- 不支援刪除 Category（僅停用）
- 若有 Ticket 關聯該 Category，應先將那些 Ticket 的 `category_id` 設為 `NULL` 或轉移至其他 Category

### 3.4 SLA 計算規則

```
sla_due_at = created_at + 對應的 SLA 小時數

例如：
- Category: "技術問題"（sla_hours_urgent = 4）
- Priority: URGENT
- Created At: 2026-09-04 10:00:00
- SLA Due At: 2026-09-04 14:00:00
```

---

## 4. API 端點設計

### 4.1 端點總覽

| 方法 | 路徑 | 說明 | 所需角色 |
|------|------|------|----------|
| `GET` | `/api/admin/categories` | 分頁查詢分類列表 | ADMIN |
| `GET` | `/api/admin/categories/{id}` | 取得單一分類 | ADMIN |
| `POST` | `/api/admin/categories` | 建立新分類 | ADMIN |
| `PUT` | `/api/admin/categories/{id}` | 更新分類（含 SLA 設定） | ADMIN |
| `PATCH` | `/api/admin/categories/{id}/deactivate` | 停用分類 | ADMIN |
| `PATCH` | `/api/admin/categories/{id}/activate` | 啟用分類 | ADMIN |
| `GET` | `/api/categories` | 取得啟用中的分類（下拉選單用） | ALL |

### 4.2 詳細規格

#### 4.2.1 GET /api/admin/categories

**Query Parameters**:
| 參數 | 型別 | 預設值 | 說明 |
|------|------|--------|------|
| `page` | int | 0 | 頁碼 |
| `size` | int | 20 | 每頁數量 |
| `sort` | string | createdAt,desc | 排序 |
| `active` | boolean | - | 依啟用狀態篩選 |
| `keyword` | string | - | 依名稱關鍵字搜尋 |

**Response**: `PageResponse<CategoryResponse>`

```json
{
  "content": [
    {
      "id": "uuid",
      "name": "技術問題",
      "description": "軟硬體技術相關問題",
      "slaHoursLow": 72,
      "slaHoursMedium": 48,
      "slaHoursHigh": 24,
      "slaHoursUrgent": 4,
      "active": true,
      "createdAt": "2026-09-04T10:00:00Z",
      "updatedAt": "2026-09-04T10:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 5,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

#### 4.2.2 GET /api/admin/categories/{id}

**Response**: `CategoryResponse`

**Error Cases**:
- `404 Not Found`: 分類不存在

#### 4.2.3 POST /api/admin/categories

**Request Body**: `CreateCategoryRequest`

```json
{
  "name": "技術問題",
  "description": "軟硬體技術相關問題",
  "slaHoursLow": 72,
  "slaHoursMedium": 48,
  "slaHoursHigh": 24,
  "slaHoursUrgent": 4
}
```

**Validation Rules**:
- `name`: 必填，長度 1-100，唯一性檢查
- `description`: 可選，長度 0-500
- `slaHoursLow`: 必填，範圍 1-720
- `slaHoursMedium`: 必填，範圍 1-720
- `slaHoursHigh`: 必填，範圍 1-720
- `slaHoursUrgent`: 必填，範圍 1-720

**Response**: `201 Created` + `CategoryResponse`

**Error Cases**:
- `400 Bad Request`: 驗證失敗
- `409 Conflict`: 分類名稱已存在

#### 4.2.4 PUT /api/admin/categories/{id}

**Request Body**: `UpdateCategoryRequest`

```json
{
  "name": "技術支援",
  "description": "更新後的描述",
  "slaHoursLow": 96,
  "slaHoursMedium": 72,
  "slaHoursHigh": 48,
  "slaHoursUrgent": 8
}
```

**Validation Rules**:
- `name`: 可選，長度 1-100，唯一性檢查（排除自己）
- `description`: 可選，長度 0-500
- `slaHoursLow`: 可選，範圍 1-720
- `slaHoursMedium`: 可選，範圍 1-720
- `slaHoursHigh`: 可選，範圍 1-720
- `slaHoursUrgent`: 可選，範圍 1-720

**Error Cases**:
- `404 Not Found`: 分類不存在
- `409 Conflict`: 分類名稱已被其他分類使用

#### 4.2.5 PATCH /api/admin/categories/{id}/deactivate

**Response**: `200 OK` + `CategoryResponse`

**Business Rules**:
- 停用後，該分類不會出現在 `GET /api/categories` 的公開列表中
- 已關聯的 Ticket 不受影響

#### 4.2.6 PATCH /api/admin/categories/{id}/activate

**Response**: `200 OK` + `CategoryResponse`

#### 4.2.7 GET /api/categories（公開端點）

**Query Parameters**:
| 參數 | 型別 | 預設值 | 說明 |
|------|------|--------|------|
| `page` | int | 0 | 頁碼 |
| `size` | int | 20 | 每頁數量 |

**Response**: `PageResponse<CategorySummaryResponse>`

**Business Rules**:
- 只回傳 `active=true` 的分類
- 不包含 SLA 詳細設定（僅用於下拉選單）

```json
{
  "content": [
    {
      "id": "uuid",
      "name": "技術問題"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 5,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

---

## 5. DTO 設計

### 5.1 Request DTOs

| DTO | 用途 |
|-----|------|
| `CreateCategoryRequest` | 建立分類（驗證名稱、SLA 時數） |
| `UpdateCategoryRequest` | 更新分類（可部分更新） |

### 5.2 Response DTOs

| DTO | 用途 |
|-----|------|
| `CategoryResponse` | 分類完整資料（含所有 SLA 設定） |
| `CategorySummaryResponse` | 分類摘要（僅 id、name，用於下拉選單） |

### 5.3 Validation Annotations

**CreateCategoryRequest:**
```java
public record CreateCategoryRequest(
    @NotBlank(message = "名稱為必填")
    @Size(min = 1, max = 100, message = "名稱長度需 1-100 字元")
    String name,
    
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

---

## 6. 實作拆分建議

### Phase 1: 資料庫變更（預計 0.5 天）

| 工作項目 | 說明 |
|----------|------|
| 建立 V3__modify_categories_add_sla.sql | 新增 Migration 為 categories 表格添加 SLA 欄位 |
| 建立 V4__seed_categories.sql | 建立預設分類資料 |

> ⚠️ **Migration 策略**：不可修改已執行的 Migration。採用新增 Migration 策略：
> - V1__create_initial_schema.sql — 保持不變（已執行）
> - V3__modify_categories_add_sla.sql — 新增 SLA 欄位
> - V4__seed_categories.sql — Seed 預設分類資料

**預計產出**:
```
src/main/resources/db/migration/
├── V1__create_initial_schema.sql    # 保持不變
├── V2__seed_users.sql              # 保持不變
├── V3__modify_categories_add_sla.sql   # 新增：新增 SLA 欄位
└── V4__seed_categories.sql           # 新增：預設分類資料
```

### Phase 2: 基礎建設（預計 1 天）

| 工作項目 | 說明 |
|----------|------|
| 建立 Category Entity | 實體映射、新增 SLA 欄位 |
| 建立 CategoryRepository | Spring Data JPA Repository |
| 建立 CategorySpecification | 動態查詢條件組合 |

**預計產出**:
```
src/main/java/com/pk/support_ticket_api/categories/
├── domain/
│   ├── Category.java
│   └── CategorySpecification.java
└── repository/
    └── CategoryRepository.java
```

### Phase 3: Service 層（預計 1 天）

| 工作項目 | 說明 |
|----------|------|
| 建立 CategoryService | CRUD 商業邏輯 |
| 建立 DTOs | CreateCategoryRequest, UpdateCategoryRequest, CategoryResponse |
| 建立 SLA Calculator | SLA 到期時間計算邏輯（供 Ticket 建立時使用） |
| 建立自訂例外 | DuplicateCategoryNameException |

**預計產出**:
```
src/main/java/com/pk/support_ticket_api/categories/
├── service/
│   ├── CategoryService.java
│   └── CategoryServiceImpl.java
├── dto/
│   ├── CreateCategoryRequest.java
│   ├── UpdateCategoryRequest.java
│   ├── CategoryResponse.java
│   └── CategorySummaryResponse.java
└── exception/
    └── CategoryException.java
```

### Phase 4: Admin API（預計 1 天）

| 工作項目 | 說明 |
|----------|------|
| 建立 CategoryAdminController | Admin 端點 |
| 設定 Security Config | 限制只有 ADMIN 可存取管理端點 |
| 建立 Public Controller | 公開端點供 Ticket 建立時選用 Category |

**預計產出**:
```
src/main/java/com/pk/support_ticket_api/categories/
└── web/
    ├── CategoryAdminController.java
    └── CategoryController.java
```

### Phase 5: 測試（預計 1 天）

| 測試類型 | 測試項目 |
|----------|----------|
| Unit Test | CategoryService 商業邏輯 |
| Unit Test | SLA Calculator 計算邏輯 |

**預計產出**:
```
src/test/java/com/pk/support_ticket_api/categories/
├── service/
│   ├── CategoryServiceTest.java
│   └── SlaCalculatorTest.java
```

---

## 7. 現有程式碼復用

| 元件 | 位置 | 用途 |
|------|------|------|
| `BaseEntity` | `common/domain/BaseEntity.java` | Category 基礎實體 |
| `TicketPriority` | `common/domain/enums/TicketPriority.java` | Priority 枚舉（LOW/MEDIUM/HIGH/URGENT） |
| `ResourceNotFoundException` | `common/exception/ResourceNotFoundException.java` | 找不到資源例外 |
| `ConflictException` | `common/exception/ConflictException.java` | 衝突例外（名稱重複） |
| `PageResponse` | `common/response/PageResponse.java` | 分頁響應格式 |
| `ValidEnum` | `common/validation/ValidEnum.java` | 自訂枚舉驗證 |

---

## 8. 與 Ticket 模組的整合

### 8.1 SLA 計算時機

Ticket 建立時呼叫 `SlaCalculator`：

```java
public class SlaCalculator {
    
    public Instant calculateSlaDueAt(Category category, TicketPriority priority, Instant createdAt) {
        int slaHours = switch (priority) {
            case LOW -> category.getSlaHoursLow();
            case MEDIUM -> category.getSlaHoursMedium();
            case HIGH -> category.getSlaHoursHigh();
            case URGENT -> category.getSlaHoursUrgent();
        };
        return createdAt.plus(slaHours, ChronoUnit.HOURS);
    }
}
```

### 8.2 Ticket 建立時的 Category 驗證

- 僅允許選用 `active=true` 的 Category
- 驗證失敗時回傳 `400 Bad Request`

---

## 9. 初始資料 Seed

### 9.1 預設分類

使用 Flyway SQL Script 建立初始資料：

```sql
-- V3__seed_categories.sql

INSERT INTO categories (id, name, description, sla_hours_low, sla_hours_medium, sla_hours_high, sla_hours_urgent, is_active, created_at, updated_at)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '技術問題', '軟硬體技術相關問題', 72, 48, 24, 4, TRUE, NOW(), NOW()),
  
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '帳務相關', '帳單、付款、發票等問題', 96, 72, 48, 8, TRUE, NOW(), NOW()),
  
  ('cccccccc-cccc-cccc-cccc-cccccccccccc', '產品諮詢', '產品功能、使用方式諮詢', 120, 72, 24, 4, TRUE, NOW(), NOW()),
  
  ('dddddddd-dddd-dddd-dddd-dddddddddddd', '功能建議', '新功能或改進建議', 168, 120, 72, 24, TRUE, NOW(), NOW()),
  
  ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', '其他', '無法分類的問題', 72, 48, 24, 4, TRUE, NOW(), NOW());
```

---

## 10. 非功能需求

### 10.1 效能

- Category 列表查詢需支援分頁（最大單頁 100 筆）
- name 唯一性檢查需建立 unique index

### 10.2 可維護性

- 遵循 package-by-feature 結構
- 所有商業邏輯放在 Service 層
- SLA 計算邏輯獨立為 `SlaCalculator` 類別

### 10.3 可測試性

- Service 層需支援 Mockito 單元測試
- SlaCalculator 需有獨立測試案例

---

## 11. 驗收標準

| ID | 標準 | 測試方式 |
|----|------|----------|
| AC-01 | Admin 可成功建立 Category（含 SLA 設定） | Unit Test |
| AC-02 | 建立 Category 時，若名稱已存在，回傳 409 Conflict | Unit Test |
| AC-03 | Admin 可分頁查詢所有 Category | Unit Test |
| AC-04 | Admin 可更新 Category 的 SLA 設定 | Unit Test |
| AC-05 | Admin 可停用/啟用 Category | Unit Test |
| AC-06 | 停用的 Category 不出現在公開列表中 | Unit Test |
| AC-07 | SLA 小時數超出 1-720 範圍時回傳 400 Bad Request | Unit Test |
| AC-08 | SlaCalculator 正確計算不同 Priority 的到期時間 | Unit Test |
| AC-09 | 所有 DTO 皆有完整的 Bean Validation | Unit Test |
| AC-10 | 系統啟動時自動建立 5 個預設分類 | Unit Test |

---

## 12. 已確認事項

| 項目 | 確認結果 |
|------|----------|
| 預設 SLA 值 | Low: 72h, Medium: 48h, High: 24h, Urgent: 4h |
| 停用 Category 行為 | 停用後現有 Ticket 可瀏覽，新建 Ticket 不可選用 |
| SLA 時數上限 | 720 小時（30 天） |
| SLA 計算方式 | 日曆時間計算，不排除非工作時間 |
| Category 刪除 | Soft Delete（設定 `active = false`），不實體刪除 |
| SLA 警告通知 | 不實作 |
| SLA 計算起點 | 建立時間起算，不排除等待客戶回覆時間 |

---

## 13. 尚未定義事項（需確認）

以下事項已無需確認，全部已決定完畢。

| 項目 | 結果 |
|------|------|
| Category 刪除 | Soft Delete，已確認 |
| SLA 警告通知 | 不實作，已確認 |
| SLA 計算起點 | 日曆時間，已確認 |

---

## 14. 相關文件

| 文件 | 位置 |
|------|------|
| 專案概覽 | `doc/overview.md` |
| 架構文件 | `doc/architecture/` |
| 其他需求文件 | `doc/requirements/` |

---

*文件版本: 1.0*
*建立日期: 2026-09-04*
*最後更新: 2026-09-04*
