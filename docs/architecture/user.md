# User Features Architecture

## 1. 架構目標與限制

### 1.1 設計目標

| 目標 | 說明 |
|------|------|
| **職責分離** | 嚴格遵循分層架構，Controller → Service → Repository |
| **可測試性** | Service 層邏輯需可透過 Mockito 隔離測試 |
| **一致性** | 遵循現有 `common` 模組的設計模式與命名慣例 |
| **安全性** | 所有 Admin API 需驗證 ADMIN 角色，密碼永不回傳 |

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
- 不得在 Response 中包含密碼欄位
- 不得使用 String 拼接 SQL（需使用 JPQL 或 Specification）
- 不得在非 Transactional 方法中進行多表寫入操作

---

## 2. 模組邊界與責任

### 2.1 模組位置

```
src/main/java/com/pk/support_ticket_api/users/
├── domain/                  # 領域模型
│   ├── User.java           # User Entity
│   └── UserSpecification.java  # Spring Data Specification
├── repository/              # 資料存取
│   └── UserRepository.java # JPA Repository
├── service/                 # 商業邏輯
│   ├── UserService.java    # Service Interface
│   └── UserServiceImpl.java # Service Implementation
├── dto/                     # 資料傳輸物件
│   ├── request/            # 請求 DTO
│   │   ├── CreateUserRequest.java
│   │   └── UpdateUserRequest.java
│   └── response/           # 回應 DTO
│       ├── UserResponse.java
│       └── UserSummaryResponse.java
├── exception/               # 模組專屬例外
│   └── UserException.java
└── web/                     # API 端點
    └── UserAdminController.java
```

### 2.2 責任矩陣

| 層級 | 元件 | 責任 |
|------|------|------|
| **Domain** | `User.java` | 實體映射、欄位約束 |
| **Domain** | `UserSpecification.java` | 動態查詢條件組合 |
| **Repository** | `UserRepository.java` | 資料庫存取介面 |
| **Service** | `UserService.java` | 商業邏輯介面定義 |
| **Service** | `UserServiceImpl.java` | CRUD、商業規則驗證 |
| **DTO** | `CreateUserRequest` | 建立使用者請求驗證 |
| **DTO** | `UpdateUserRequest` | 更新使用者請求驗證 |
| **DTO** | `UserResponse` | 使用者詳細資料回應 |
| **DTO** | `UserSummaryResponse` | 使用者摘要回應 |
| **Controller** | `UserAdminController.java` | HTTP 協商、路由 |

---

## 3. 依賴方向

### 3.1 依賴規則

```
┌─────────────────────────────────────────────────────────┐
│                     Controller                          │
│              (依賴 Service、DTO、CurrentUser)            │
└─────────────────────────┬───────────────────────────────┘
                          │ 依賴
┌─────────────────────────▼───────────────────────────────┐
│                     Service                             │
│           (依賴 Repository、Entity、Exception)           │
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
| `VersionedEntity` | `common/domain/VersionedEntity.java` | Optimistic Locking（未來需要時） |
| `Role` | `common/domain/enums/Role.java` | 角色枚舉 |
| `CurrentUser` | `common/security/CurrentUser.java` | 當前登入者資訊 |
| `PageResponse` | `common/response/PageResponse.java` | 分頁響應格式 |
| `ResourceNotFoundException` | `common/exception/ResourceNotFoundException.java` | 資源不存在例外 |
| `ConflictException` | `common/exception/ConflictException.java` | 衝突例外（email 重複） |
| `BusinessRuleException` | `common/exception/BusinessRuleException.java` | 商業規則違反例外 |

### 3.3 被依賴關係

| 元件 | 被誰依賴 |
|------|----------|
| `User` Entity | Ticket（created_by, assigned_to 外鍵） |
| `Role` enum | Auth 模組、Ticket 模組 |

---

## 4. 核心資料模型的責任歸屬

### 4.1 User Entity

```java
@Entity
@Table(name = "users")
public class User extends BaseEntity {
    
    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;
    
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;
}
```

### 4.2 欄位職責歸屬

| 欄位 | 產生方式 | 驗證責任 | 業務約束 |
|------|----------|----------|----------|
| `id` | Database (UUID) | - | 唯讀，不可修改 |
| `email` | 建立時由用戶提供 | Service 層唯一性檢查 | 需建立 Unique Index |
| `passwordHash` | Service 層 BCrypt 雜湊 | - | 強度 10，永不回傳 |
| `displayName` | 建立/更新時提供 | Bean Validation (@Size 2-100) | - |
| `role` | 建立時指定 | Bean Validation (有效 enum 值) | - |
| `status` | 預設 ACTIVE | - | 由 activate/deactivate 操作修改 |
| `createdAt` | JPA @CreatedDate | - | 唯讀 |
| `updatedAt` | JPA @LastModifiedDate | - | 自動更新 |

### 4.3 枚舉定義

```java
// 角色枚舉（已存在於 common）
public enum Role {
    CUSTOMER,  // 一般客戶
    AGENT,     // 客服人員
    ADMIN      // 系統管理員
}

// 使用者狀態（需新建）
public enum UserStatus {
    ACTIVE,    // 正常
    INACTIVE,  // 停用
    SUSPENDED  // 停權
}
```

---

## 5. 主要資料流程

### 5.1 建立使用者流程

```
Client POST /api/admin/users
        │
        ▼
┌─────────────────────────────────────────────────┐
│ UserAdminController                             │
│  - @PreAuthorize("hasRole('ADMIN')")           │
│  - @Valid CreateUserRequest                     │
│  - 呼叫 userService.createUser(request)         │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│ UserServiceImpl.createUser()                    │
│  1. 驗證 email 唯一性（repository.existsByEmail）│
│  2. 若衝突 → 拋出 ConflictException             │
│  3. 密碼 BCrypt 雜湊                            │
│  4. 建立 User Entity                            │
│  5. repository.save(user)                       │
│  6. 記錄 Audit Log                              │
│  7. 回傳 UserResponse                           │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
Response: 201 Created + UserResponse
```

### 5.2 停用使用者流程

```
Client PATCH /api/admin/users/{id}/deactivate
        │
        ▼
┌─────────────────────────────────────────────────┐
│ UserAdminController                             │
│  - 驗證 ADMIN 角色                              │
│  - 呼叫 userService.deactivate(id)              │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│ UserServiceImpl.deactivate()                    │
│  1. 查詢使用者（findById）                      │
│  2. 若不存在 → ResourceNotFoundException        │
│  3. 驗證不可停用自己（CurrentUser）             │
│  4. 驗證不可停用超級管理員                      │
│  5. 若規則違反 → BusinessRuleException          │
│  6. 更新 status = INACTIVE                      │
│  7. repository.save(user)                       │
│  8. 記錄 Audit Log                              │
│  9. 回傳 UserResponse                           │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
Response: 200 OK + UserResponse
```

### 5.3 分頁查詢流程

```
Client GET /api/admin/users?page=0&size=20&role=AGENT&keyword=john
        │
        ▼
┌─────────────────────────────────────────────────┐
│ UserAdminController                             │
│  - Pageable 參數自動绑定                        │
│  - 呼叫 userService.findAll(spec, pageable)     │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│ UserServiceImpl.findAll()                       │
│  1. 建立 UserSpecification（動態組合條件）      │
│  2. repository.findAll(spec, pageable)          │
│  3. Page<User> → PageResponse<UserResponse>     │
│  4. 回傳分頁結果                                │
└─────────────────────────────────────────────────┘
```

### 5.4 Specification 條件組合

```java
public class UserSpecification {
    
    public static Specification<User> withFilters(
            Role role,
            UserStatus status,
            String keyword
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            
            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
            }
            
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword.toLowerCase() + "%";
                Predicate emailMatch = cb.like(
                    cb.lower(root.get("email")), pattern);
                Predicate nameMatch = cb.like(
                    cb.lower(root.get("displayName")), pattern);
                predicates.add(cb.or(emailMatch, nameMatch));
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
| `GET` | `/api/admin/users` | 分頁查詢使用者 | ADMIN |
| `GET` | `/api/admin/users/{id}` | 取得單一使用者 | ADMIN |
| `POST` | `/api/admin/users` | 建立新使用者 | ADMIN |
| `PUT` | `/api/admin/users/{id}` | 更新使用者資料 | ADMIN |
| `PATCH` | `/api/admin/users/{id}/deactivate` | 停用使用者 | ADMIN |
| `PATCH` | `/api/admin/users/{id}/activate` | 啟用使用者 | ADMIN |
| `DELETE` | `/api/admin/users/{id}` | 刪除使用者（Soft Delete） | ADMIN |

### 6.2 錯誤響應格式

所有錯誤使用 `common/exception/ApiExceptionHandler` 統一處理：

| HTTP Status | 例外類型 | 錯誤格式 |
|-------------|----------|----------|
| 400 | ValidationException | `{"errors": [...], "timestamp": "...", "traceId": "..."}` |
| 401 | AuthenticationException | `{"message": "Unauthorized", "traceId": "..."}` |
| 403 | AccessDeniedException | `{"message": "Forbidden", "traceId": "..."}` |
| 404 | ResourceNotFoundException | `{"message": "User not found", "traceId": "..."}` |
| 409 | ConflictException | `{"message": "Email already exists", "traceId": "..."}` |
| 422 | BusinessRuleException | `{"message": "Cannot deactivate yourself", "traceId": "..."}` |

### 6.3 例外處理責任

| 例外 | 拋出位置 | 訊息格式 |
|------|----------|----------|
| `ResourceNotFoundException` | Service | `"User not found with id: {id}"` |
| `ConflictException` | Service | `"Email already exists: {email}"` |
| `BusinessRuleException` | Service | `"Cannot deactivate the last admin user"` |
| `ValidationException` | DTO (Bean Validation) | 欄位層級錯誤 |

### 6.4 Validation 規則

**CreateUserRequest:**
```java
public record CreateUserRequest(
    @NotBlank(message = "Email 為必填")
    @Email(message = "無效的 email 格式")
    String email,
    
    @NotBlank(message = "密碼為必填")
    @Size(min = 8, max = 100, message = "密碼長度需 8-100 字元")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
             message = "密碼需包含大小寫字母及數字")
    String password,
    
    @NotBlank(message = "名稱為必填")
    @Size(min = 2, max = 100, message = "名稱長度需 2-100 字元")
    String displayName,
    
    @NotNull(message = "角色為必填")
    Role role
) {}
```

**UpdateUserRequest:**
```java
public record UpdateUserRequest(
    @Email(message = "無效的 email 格式")
    String email,
    
    @Size(min = 2, max = 100, message = "名稱長度需 2-100 字元")
    String displayName,
    
    Role role
) {}
```

---

## 7. 測試策略

### 7.1 Unit Test — UserService

**測試檔案**: `src/test/java/com/pk/support_ticket_api/users/service/UserServiceTest.java`

| 測試案例 | 測試內容 |
|----------|----------|
| `createUser_Success` | 正常建立使用者，回傳正確 Response |
| `createUser_DuplicateEmail` | email 重複時拋出 ConflictException |
| `createUser_InvalidRole` | 無效角色時拋出 ValidationException |
| `updateUser_Success` | 正常更新，回傳更新後資料 |
| `updateUser_EmailToExisting` | 更新為已存在的 email 時拋出 ConflictException |
| `deactivate_Success` | 正常停用，status 變為 INACTIVE |
| `deactivate_Yourself` | 嘗試停用自己拋出 BusinessRuleException |
| `deactivate_LastAdmin` | 停用最後一個 Admin 拋出 BusinessRuleException |
| `activate_Success` | 正常啟用，status 變為 ACTIVE |
| `delete_Success` | Soft Delete 設定 status 為 INACTIVE |
| `delete_Yourself` | 嘗試刪除自己拋出 BusinessRuleException |
| `delete_LastAdmin` | 刪除最後一個 Admin 拋出 BusinessRuleException |
| `findAll_WithFilters` | 分頁查詢正確套用 Specification 條件 |

### 7.2 測試資料 Seed

使用 Flyway `V2__seed_users.sql` 建立測試資料：

```sql
INSERT INTO users (id, email, password_hash, display_name, role, status, created_at, updated_at)
VALUES
  ('11111111-1111-1111-1111-111111111111', 'admin@example.com', 
   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
   'System Admin', 'ADMIN', 'ACTIVE', NOW(), NOW()),
  
  ('22222222-2222-2222-2222-222222222222', 'agent@example.com', 
   '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG',
   'Support Agent', 'AGENT', 'ACTIVE', NOW(), NOW()),
  
  ('33333333-3333-3333-3333-333333333333', 'customer@example.com', 
   '$2a$10$EqKcp1WFKVQISheBxkQYou.//N8HJxZQ9xO4HJyO3xq8x8Z8Z8Z8Z',
   'Happy Customer', 'CUSTOMER', 'ACTIVE', NOW(), NOW());
```

---

## 8. 禁止事項與待確認決策

### 8.1 禁止事項（Must Not）

| 項目 | 原因 | 替代方案 |
|------|------|----------|
| 不得在 Response 中回傳 `passwordHash` | 安全風險 | 使用 `UserResponse`（不含密碼欄位） |
| 不得在 URL 暴露 `password` 參數 | 安全風險 | 使用 Request Body POST/PUT |
| 不得在 GET 請求中傳遞敏感資料 | 日誌記錄風險 | 使用 POST 查詢端點 |
| 不得繞過 Bean Validation | 資料一致性 | 所有輸入皆需驗證 |
| 不得使用 String concatenation 組 SQL | SQL Injection | 使用 JPQL/Specification/QueryDSL |
| 不得在同一 Transaction 寫入後再讀取 | 一致性風險 | 設計合理的 Transaction 邊界 |
| 不得硬編碼 Business Rule 數值 | 可維護性 | 提取為 Configuration Properties |

### 8.2 待確認決策

| 項目 | 問題描述 | 選項 |
|------|----------|------|
| **密碼複雜度** | 除了長度外是否需要特殊字元？ | A: 需要特殊字元 |
| **刪除邏輯** | 刪除有工單關聯的使用者如何處理？ | A: 阻擋刪除 / B: 轉移工單後刪除 / C: 允許刪除（cascade） |
| **自我操作限制** | Admin 是否可修改自己的角色？ | A: 禁止 |
| **超級管理員** | 是否需要區分超級管理員與一般管理員？ | A: 不需要 / B: 需要（無法停用/刪除） |
| **登入失敗鎖定** | 是否實作帳號鎖定機制？ | A: 不實作 / B: 5次失敗鎖定5分鐘 |
| **Audit Log 詳細度** | 密碼變更是否需要記錄？ | A: 記錄變更事實 / B: 不記錄（安全） |

### 8.3 假設條件

在取得確認前，以下為預設假設：

| 項目 | 預設值 |
|------|--------|
| 密碼複雜度 | 8-100 字元，包含大小寫字母及數字 |
| 刪除邏輯 | Soft Delete（設定 status = INACTIVE），關聯工單保留 |
| 自我操作限制 | 禁止停用/刪除自己 |
| 超級管理員 | 不區分，所有 ADMIN 權限相同 |
| 登入失敗鎖定 | 本版本不實作 |
| Audit Log | 記錄建立、更新、刪除事件 |

---

## 9. 附錄

### 9.1 檔案清單

| 檔案 | 位置 | 說明 |
|------|------|------|
| BaseEntity | `common/domain/BaseEntity.java` | 實體基底類別 |
| Role | `common/domain/enums/Role.java` | 角色枚舉 |
| CurrentUser | `common/security/CurrentUser.java` | 當前用戶資訊 |
| PageResponse | `common/response/PageResponse.java` | 分頁響應 |
| ApiExceptionHandler | `common/exception/ApiExceptionHandler.java` | 統一例外處理 |
| User | `users/domain/User.java` | 使用者實體 |
| UserRepository | `users/repository/UserRepository.java` | 資料存取 |
| UserService | `users/service/UserService.java` | 服務介面 |
| UserServiceImpl | `users/service/UserServiceImpl.java` | 服務實作 |
| UserAdminController | `users/web/UserAdminController.java` | Admin API |
| V2__seed_users | `resources/db/migration/V2__seed_users.sql` | 測試資料 |

### 9.2 參考文件

| 文件 | 位置 |
|------|------|
| 專案概覽 | `doc/overview.md` |
| 使用者需求 | `doc/requirements/user.md` |
| 初始資料庫遷移 | `src/main/resources/db/migration/V1__create_initial_schema.sql` |

---

*文件版本: 1.0*
*建立日期: 2026-08-27*
*最後更新: 2026-08-27*
