# User Module - 功能需求規格書

## 1. 模組概述

### 1.1 目的
建立完整的 **User 模組**，支援多角色（Customer、Agent、Admin）的使用者管理功能，並提供 Admin 等級的管理 API 以及系統初始化資料。

### 1.2 技術上下文
- **Framework**: Java 21 + Spring Boot 4.1.1
- **架構模式**: Modular Monolith（package-by-feature）
- **認證**: Stateless JWT Authentication（由 `auth` 模組提供）
- **ORM**: Spring Data JPA + Hibernate
- **資料庫**: PostgreSQL + Flyway
- **驗證**: Bean Validation

---

## 2. 資料模型

### 2.1 User Entity

| 欄位 | 型別 | 約束 | 說明 |
|------|------|------|------|
| `id` | UUID | PK, auto-generated | 主鍵 |
| `email` | String | unique, not null, max 255 | 登入信箱 |
| `passwordHash` | String | not null | BCrypt 雜湊後密碼 |
| `name` | String | not null, max 100 | 顯示名稱 |
| `role` | Enum (Role) | not null | CUSTOMER / AGENT / ADMIN |
| `status` | Enum (UserStatus) | not null, default ACTIVE | ACTIVE / INACTIVE / SUSPENDED |
| `createdAt` | Instant | not null, auto | 建立時間 |
| `updatedAt` | Instant | not null, auto | 更新時間 |

### 2.2 既有結構復用
- 繼承 `BaseEntity`（已定義 `id`, `createdAt`, `updatedAt`）
- 使用 `Role` enum（已定義 CUSTOMER, AGENT, ADMIN）
- 使用 `VersionedEntity` 若需要 optimistic locking

---

## 3. Admin 管理 API

### 3.1 API 端點總覽

| 方法 | 路徑 | 說明 | 所需角色 |
|------|------|------|----------|
| `GET` | `/api/admin/users` | 分頁查詢使用者列表 | ADMIN |
| `GET` | `/api/admin/users/{id}` | 取得單一使用者 | ADMIN |
| `POST` | `/api/admin/users` | 建立新使用者 | ADMIN |
| `PUT` | `/api/admin/users/{id}` | 更新使用者資料 | ADMIN |
| `PATCH` | `/api/admin/users/{id}/deactivate` | 停用使用者 | ADMIN |
| `PATCH` | `/api/admin/users/{id}/activate` | 啟用使用者 | ADMIN |
| `DELETE` | `/api/admin/users/{id}` | 刪除使用者（Soft Delete） | ADMIN |

### 3.2 詳細規格

#### 3.2.1 GET /api/admin/users

**Query Parameters**:
| 參數 | 型別 | 預設值 | 說明 |
|------|------|--------|------|
| `page` | int | 0 | 頁碼 |
| `size` | int | 20 | 每頁數量 |
| `sort` | string | createdAt,desc | 排序 |
| `role` | string | - | 依角色篩選 |
| `status` | string | - | 依帳號狀態篩選（ACTIVE/INACTIVE/SUSPENDED） |
| `keyword` | string | - | 依 name 或 email 關鍵字搜尋 |

**Response**: `PageResponse<UserResponse>`

```json
{
  "content": [
    {
      "id": "uuid",
      "email": "user@example.com",
      "name": "John Doe",
      "role": "AGENT",
      "status": "ACTIVE",
      "createdAt": "2026-08-27T10:00:00Z",
      "updatedAt": "2026-08-27T10:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 50,
  "totalPages": 3,
  "sort": "createdAt,desc"
}
```

#### 3.2.2 GET /api/admin/users/{id}

**Response**: `UserResponse`

**Error Cases**:
- `404 Not Found`: 使用者不存在

#### 3.2.3 POST /api/admin/users

**Request Body**: `CreateUserRequest`

```json
{
  "email": "newuser@example.com",
  "password": "SecurePass123!",
  "name": "New User",
  "role": "AGENT"
}
```

**Validation Rules**:
- `email`: 必填，有效 email 格式，唯一性檢查
- `password`: 必填，長度 8-100，含大小寫字母及數字
- `name`: 必填，長度 2-100
- `role`: 必填，須為有效 Role 值

**Response**: `201 Created` + `UserResponse`

**Error Cases**:
- `400 Bad Request`: 驗證失敗
- `409 Conflict`: email 已存在

#### 3.2.4 PUT /api/admin/users/{id}

**Request Body**: `UpdateUserRequest`

```json
{
  "email": "updated@example.com",
  "name": "Updated Name",
  "role": "ADMIN"
}
```

**Validation Rules**:
- `email`: 可選，有效 email 格式，唯一性檢查（排除自己）
- `name`: 可選，長度 2-100
- `role`: 可選，須為有效 Role 值

**Error Cases**:
- `404 Not Found`: 使用者不存在
- `409 Conflict`: email 已被其他使用者使用

#### 3.2.5 PATCH /api/admin/users/{id}/deactivate

**Response**: `200 OK` + `UserResponse`

**Business Rules**:
- 停用後使用者無法登入
- 不可停用自己（防止鎖定最後一個 Admin）
- 不可停用超級管理員帳號（若有的話）

#### 3.2.6 PATCH /api/admin/users/{id}/activate

**Response**: `200 OK` + `UserResponse`

#### 3.2.7 DELETE /api/admin/users/{id}

**Response**: `204 No Content`

**Business Rules**:
- 實作 Soft Delete（設定 `status = INACTIVE`）
- 不可刪除自己
- 不可刪除最後一個 Admin

---

## 4. DTO 設計

### 4.1 Request DTOs

| DTO | 用途 |
|-----|------|
| `CreateUserRequest` | 建立使用者（驗證 email, password, name, role） |
| `UpdateUserRequest` | 更新使用者（可部分更新） |

### 4.2 Response DTOs

| DTO | 用途 |
|-----|------|
| `UserResponse` | 使用者詳細資料（含 id, email, name, role, status, timestamps） |
| `UserSummaryResponse` | 使用者摘要（用於下拉選單等） |

### 4.3 Validation Annotations
```java
CreateUserRequest:
- @Email(message = "無效的 email 格式")
- @NotBlank(message = "email 為必填")
- @NotBlank @Size(min = 8, max = 100, message = "密碼長度需 8-100 字元")
- @NotBlank @Size(min = 2, max = 100, message = "名稱長度需 2-100 字元")
- @NotNull @ValidEnum(enumClass = Role.class, message = "無效的角色")
```

---

## 5. 初始資料 Seed

### 5.1 Demo 帳號

使用 Flyway SQL Script 建立初始資料：

```sql
-- V2__seed_users.sql

INSERT INTO users (id, email, password_hash, name, role, status, created_at, updated_at)
VALUES
  ('11111111-1111-1111-1111-111111111111', 'admin@example.com', 
   -- BCrypt('admin123')
   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
   'System Admin', 'ADMIN', 'ACTIVE', NOW(), NOW()),
  
  ('22222222-2222-2222-2222-222222222222', 'agent@example.com', 
   -- BCrypt('agent123')
   '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG',
   'Support Agent', 'AGENT', 'ACTIVE', NOW(), NOW()),
  
  ('33333333-3333-3333-3333-333333333333', 'customer@example.com', 
   -- BCrypt('customer123')
   '$2a$10$EqKcp1WFKVQISheBxkQYou.//N8HJxZQ9xO4HJyO3xq8x8Z8Z8Z8Z',
   'Happy Customer', 'CUSTOMER', 'ACTIVE', NOW(), NOW());
```

### 5.2 Seed 原則
- 所有密碼皆為 BCrypt 雜湊（可使用線上工具產生）
- 使用固定 UUID，方便自動化測試
- 資料庫遷移檔案置於 `src/main/resources/db/migration/`

---

## 6. 實作拆分建議

### Phase 1: 基礎建設（預計 1-2 天）

| 工作項目 | 說明 |
|----------|------|
| 建立 User Entity | 繼承 BaseEntity，新增 email, passwordHash, name, role, status |
| 建立 UserRepository | Spring Data JPA Repository |
| 建立 Flyway Migration | V2__seed_users.sql |
| 建立 Role Enum（已存在） | 確認 Role enum 完整 |

**預計產出**:
```
src/main/java/com/pk/support_ticket_api/users/
├── domain/
│   └── User.java
├── repository/
│   └── UserRepository.java
src/main/resources/db/migration/
└── V2__seed_users.sql
```

### Phase 2: Service 層（預計 1 天）

| 工作項目 | 說明 |
|----------|------|
| 建立 UserService | CRUD 商業邏輯 |
| 建立 DTOs | CreateUserRequest, UpdateUserRequest, UserResponse |
| 建立自訂例外 | DuplicateEmailException |
| 建立 EmailUniquenessValidator | 自訂驗證器 |

**預計產出**:
```
src/main/java/com/pk/support_ticket_api/users/
├── service/
│   ├── UserService.java
│   └── UserServiceImpl.java
└── dto/
    ├── CreateUserRequest.java
    ├── UpdateUserRequest.java
    ├── UserResponse.java
    └── validation/
        └── UniqueEmailValidator.java
```

### Phase 3: Admin API（預計 1-2 天）

| 工作項目 | 說明 |
|----------|------|
| 建立 UserAdminController | Admin 端點 |
| 設定 Security Config | 限制只有 ADMIN 可存取 |
| 建立 Query Specification | 支援搜尋/篩選 |
| API 文件標註 | @Operation, @ApiResponse |

**預計產出**:
```
src/main/java/com/pk/support_ticket_api/users/
└── web/
    └── UserAdminController.java
```

### Phase 4: 測試（預計 1 天）

| 測試類型 | 測試項目 |
|----------|----------|
| Unit Test | UserService 商業邏輯 |

**預計產出**:
```
src/test/java/com/pk/support_ticket_api/users/
└── service/
    └── UserServiceTest.java
```

---

## 7. 現有程式碼復用

| 元件 | 位置 | 用途 |
|------|------|------|
| `BaseEntity` | `common/domain/BaseEntity.java` | User 基礎實體 |
| `Role` | `common/domain/enums/Role.java` | 角色枚舉 |
| `ResourceNotFoundException` | `common/exception/ResourceNotFoundException.java` | 找不到資源例外 |
| `ConflictException` | `common/exception/ConflictException.java` | 衝突例外（可用於 email 衝突） |
| `PageResponse` | `common/response/PageResponse.java` | 分頁響應格式 |
| `CurrentUser` | `common/security/CurrentUser.java` | 目前登入者資訊 |

---

## 8. 安全性考量

### 8.1 授權檢查
- 所有 Admin API 需驗證 `CurrentUser.role == ADMIN`
- 使用 `@PreAuthorize("hasRole('ADMIN')")` 或自訂 Method Security

### 8.2 密碼安全
- 密碼需 BCrypt 雜湊（強度 10）
- 響應中永遠不包含密碼欄位

### 8.3 資料隔離
- 不可列舉所有使用者（需分頁）
- 敏感操作需留下 Audit Log

---

## 9. 非功能需求

### 9.1 效能
- 使用者列表查詢需支援分頁（最大單頁 100 筆）
- email 唯一性檢查需建立 unique index

### 9.2 可維護性
- 遵循 package-by-feature 結構
- 所有商業邏輯放在 Service 層
- Controller 只處理 HTTP 協定向

### 9.3 可測試性
- Service 層需支援 Mockito 單元測試

---

## 10. 依賴關係

### 10.1 外部依賴
本模組無需新增外部依賴，使用現有：
- Spring Data JPA
- Spring Security
- Bean Validation

### 10.2 內部依賴
- `common` 模組（所有通用元件）
- 無其他功能模組依賴

---

## 11. 驗收標準

| ID | 標準 | 測試方式 |
|----|------|----------|
| AC-01 | Admin 可成功建立 CUSTOMER、AGENT、ADMIN 角色使用者 | Unit Test |
| AC-02 | 建立使用者時，若 email 已存在，回傳 409 Conflict | Unit Test |
| AC-03 | Admin 可分頁查詢所有使用者 | Unit Test |
| AC-04 | Admin 可依 role、status、keyword 篩選使用者 | Unit Test |
| AC-05 | Admin 可停用/啟用使用者 | Unit Test |
| AC-06 | 非 ADMIN 角色存取 Admin API 回傳 403 Forbidden | Unit Test |
| AC-07 | 系統啟動時自動建立 3 個 Demo 帳號 | Unit Test |
| AC-08 | 所有 DTO 皆有完整的 Bean Validation | Unit Test |

---

## 12. 尚未定義事項（需確認）

以下事項尚未確定，建議與相關人員確認：

| 項目 | 問題 |
|------|------|
| 密碼複雜度 | 除了長度外，是否需要特殊字元、大寫等強制規則？ |
| 刪除邏輯 | 刪除有工單的使用者時如何處理？（轉移/阻擋） |
| 自我操作限制 | Admin 是否可修改自己的角色？ |
| 超級管理員 | 是否需要區分「超級管理員」與「一般管理員」？ |

---

## 13. 相關文件

| 文件 | 位置 |
|------|------|
| 專案概覽 | `doc/overview.md` |
| 架構文件 | `doc/architecture/`|
| 其他需求文件 | `doc/requirements/` |

---

*文件版本: 1.0*
*建立日期: 2026-08-27*
*最後更新: 2026-08-27*
