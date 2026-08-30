# User 模組實作任務計畫

## 概述

根據 `doc/requirements/user.md` 及 `doc/architecture/user.md` 的實作拆分建議，將 User 模組分為以下獨立任務。

---

## Task 1: User 基礎建設 - 已完成實作

### 描述
建立 User Entity、Repository、以及 Seed Migration。

### In scope
- 建立 `User` Entity，繼承 `BaseEntity`
- 建立 `UserRepository` interface
- 建立 `V2__seed_users.sql` 初始資料遷移檔
- 建立 `UserSpecification` 動態查詢類

### Out of scope
- Service 層商業邏輯
- DTO 類別
- Controller 層
- 測試程式碼

### Expected files
```
src/main/java/com/pk/support_ticket_api/users/
├── domain/
│   └── User.java
├── repository/
│   └── UserRepository.java
└── UserSpecification.java
src/main/resources/db/migration/
└── V2__seed_users.sql
```

### Dependencies
- `BaseEntity` - 已存在
- `Role` enum - 已存在
- `V1__create_initial_schema.sql` - 已存在（users 表已建立）

### Acceptance criteria
- [x] `User` Entity 可正確映射 `users` 表（id, email, passwordHash, displayName, role, **status**, createdAt, updatedAt）
- [x] `User` 使用 `status` Enum（UserStatus：ACTIVE/INACTIVE/SUSPENDED）
- [x] `UserRepository` 支援 `findAll(Specification, Pageable)` 用於分頁查詢
- [x] `UserRepository` 支援 `existsByEmail()` 用於唯一性檢查
- [x] `UserSpecification` 支援 role、**status**、keyword 篩選條件
- [x] `V2__seed_users.sql` 建立 3 個 Demo 帳號（admin, agent, customer），使用 `status` VARCHAR
- [x] 程式可正常編譯

### 風險與待確認
1. **V1 migration 相容性**：
   - V1 已使用 `status` VARCHAR，符合 UserStatus Enum 設計
   - 或直接建立 `V2__seed_users.sql` 並加入 ALTER TABLE 指令
2. **BCrypt 密碼 hash**：需產生正確的 hash 值

---

## Task 2: DTO 類別建立- - 已完成實作

### 描述
建立所有 Request/Response DTO 類別，包含 Bean Validation 驗證規則。

### In scope
- 建立 `CreateUserRequest` record
- 建立 `UpdateUserRequest` record
- 建立 `UserResponse` record
- 建立密碼驗證自訂註解（如需要）

### Out of scope
- Service 層商業邏輯
- Validator 實作類別
- Controller 層

### Expected files
```
src/main/java/com/pk/support_ticket_api/users/
├── dto/
│   ├── CreateUserRequest.java
│   ├── UpdateUserRequest.java
│   └── UserResponse.java
```

### Dependencies
- Task 1: 需先完成（無程式碼依賴，但 DTO 設計需配合 Entity）
- `Role` enum - 已存在

### Acceptance criteria
- [x]`CreateUserRequest` 包含 email、password、displayName、role 欄位
- [x]`UpdateUserRequest` 包含 email、displayName、role 欄位（皆可選）
- [x]`UserResponse` 包含 id、email、displayName、role、**status**、createdAt、updatedAt（不含密碼）
- [x]所有必填欄位有正確的 Bean Validation 註解
- [x]email 格式驗證使用 `@Email`
- [x]密碼複雜度驗證（大小寫+數字）

### 風險與待確認
1. **密碼複雜度**：只需要大小寫字母及數字（`(?=.*[a-z])(?=.*[A-Z])(?=.*\d)`），不需特殊字元
2. **displayName vs name**：Entity 使用 `displayName`，需確認所有地方一致

---

## Task 3: Service 層實作

### 描述
建立 UserService 介面與實作，包含所有商業邏輯。

### In scope
- 建立 `UserService` interface
- 建立 `UserServiceImpl` 實作類
- 實作 CRUD 商業邏輯
- 實作停用/啟用/刪除商業規則

### Out of scope
- Controller 層
- 測試程式碼
- Audit Log 記錄功能（由 audit 模組獨立實作）

### Expected files
```
src/main/java/com/pk/support_ticket_api/users/
├── service/
│   ├── UserService.java
│   └── UserServiceImpl.java
```

### Dependencies
- Task 1: User Entity, UserRepository, UserSpecification
- Task 2: DTO 類別
- `ResourceNotFoundException` - 已存在
- `ConflictException` - 已存在
- `BusinessRuleException` - 已存在
- `PageResponse` - 已存在

### Acceptance criteria
- [ ] `createUser()` - 建立新使用者，密碼 BCrypt 雜湊，檢查 email 唯一性
- [ ] `updateUser()` - 更新使用者資料，檢查 email 唯一性（排除自己）
- [ ] `deactivate()` - 停用使用者（不可停用自己、不可停用最後一個 Admin）
- [ ] `activate()` - 啟用使用者
- [ ] `delete()` - Soft Delete（設定 status=INACTIVE，不可刪除自己、不可刪除最後一個 Admin）
- [ ] `findAll()` - 分頁查詢支援 Specification 篩選
- [ ] `findById()` - 取得單一使用者
- [ ] 所有操作皆有對應的例外拋出

### 風險與待確認
1. **自我操作限制**：停用/刪除自己是否為 BusinessRuleException？需與需求一致
2. **最後一個 Admin 檢查**：需計算目前 Admin 數量是否 > 1

---

## Task 4: Admin API Controller

### 描述
建立 UserAdminController，實作所有 Admin API 端點。

### In scope
- 建立 `UserAdminController`
- 設定 `@PreAuthorize("hasRole('ADMIN')")` 權限控制
- API 文件標註（@Operation, @ApiResponse）

### Out of scope
- Security Config 調整（假設已由 auth 模組設定）
- 測試程式碼

### Expected files
```
src/main/java/com/pk/support_ticket_api/users/
└── web/
    └── UserAdminController.java
```

### Dependencies
- Task 2: DTO 類別
- Task 3: UserService
- `CurrentUser` - 已存在

### Acceptance criteria
- [ ] `GET /api/admin/users` - 分頁查詢，回傳 `PageResponse<UserResponse>`
- [ ] `GET /api/admin/users/{id}` - 取得單一使用者
- [ ] `POST /api/admin/users` - 建立新使用者，回傳 201 + UserResponse
- [ ] `PUT /api/admin/users/{id}` - 更新使用者資料
- [ ] `PATCH /api/admin/users/{id}/deactivate` - 停用使用者
- [ ] `PATCH /api/admin/users/{id}/activate` - 啟用使用者
- [ ] `DELETE /api/admin/users/{id}` - Soft Delete，回傳 204
- [ ] 所有端點需要 ADMIN 角色
- [ ] Swagger UI 可看到 API 文件

### 風險與待確認
1. **API 前綴**：需求文件使用 `/api/admin/users`，需確認與現有 Security Config 是否一致

---

## Task 5: 單元測試

### 描述
建立 UserService 的單元測試。

### In scope
- 建立 `UserServiceTest` 測試類
- 測試所有 Service 方法

### Out of scope
- Repository Integration Test
- Controller API Test
- Testcontainers 設定

### Expected files
```
src/test/java/com/pk/support_ticket_api/users/
└── service/
    └── UserServiceTest.java
```

### Dependencies
- Task 3: UserService 實作
- Mockito - 專案已有 Spring Boot Test

### Acceptance criteria
- [ ] `createUser_Success` - 正常建立使用者
- [ ] `createUser_DuplicateEmail` - email 重複拋出 ConflictException
- [ ] `updateUser_Success` - 正常更新
- [ ] `updateUser_EmailToExisting` - 更新為已存在 email 拋出 ConflictException
- [ ] `deactivate_Success` - 正常停用
- [ ] `deactivate_Yourself` - 停用自己拋出 BusinessRuleException
- [ ] `deactivate_LastAdmin` - 停用最後一個 Admin 拋出 BusinessRuleException
- [ ] `activate_Success` - 正常啟用
- [ ] `delete_Success` - Soft Delete
- [ ] `delete_Yourself` - 刪除自己拋出 BusinessRuleException
- [ ] `delete_LastAdmin` - 刪除最後一個 Admin 拋出 BusinessRuleException
- [ ] `findAll_WithFilters` - 分頁查詢正確套用條件
- [ ] `findById_NotFound` - 找不到使用者拋出 ResourceNotFoundException

### 風險與待確認
- 測試隔離：需使用 `@ExtendWith(MockitoExtension.class)` 確保 Mockito 正確運作

---

## Task 6: Integration Test

### 描述
建立 Repository 和 Controller 的 Integration Test。

### In scope
- 建立 `UserRepositoryTest` - 使用 Testcontainers PostgreSQL
- 建立 `UserAdminControllerTest` - 使用 @WebMvcTest + @WithMockUser

### Out of scope
- E2E Test
- 效能測試

### Expected files
```
src/test/java/com/pk/support_ticket_api/users/
├── repository/
│   └── UserRepositoryTest.java
└── web/
    └── UserAdminControllerTest.java
```

### Dependencies
- Task 1: User Entity, UserRepository
- Task 4: UserAdminController
- Testcontainers PostgreSQL - 需確認專案已設定

### Acceptance criteria
- [ ] Repository Test: save、findById、existsByEmail 功能正確
- [ ] Repository Test: email 唯一性約束有效
- [ ] Controller Test: ADMIN 角色可成功操作（201/200）
- [ ] Controller Test: AGENT/CUSTOMER 角色存取回傳 403
- [ ] Controller Test: 未登入回傳 401
- [ ] Controller Test: Validation 錯誤回傳 400

### 風險與待確認
1. **Testcontainers 設定**：需確認 pom.xml 已包含 testcontainers 依賴
2. **測試資料隔離**：每個測試需獨立，需使用 @Transactional 自動回滾

---

## 已確認決策

| # | 項目 | 決策 | 原因 |
|---|------|------|------|
| 1 | Status 欄位 | `status` Enum (UserStatus) | 三種狀態，符合需求規格 |
| 2 | 密碼複雜度 | 至少 8 碼，不強制特殊字元 | 提供基本防護，避免過度複雜的規則 |
| 3 | 刪除邏輯 | Soft Delete（`status=INACTIVE`） | 保留 Ticket、Comment、Audit Log 的歷史關聯 |
| 4 | 超級管理員 | MVP 不區分 | ADMIN 已足夠，避免權限模型過早複雜化 |

### 對計畫的影響

1. **V2__seed_users.sql**：使用 `status` VARCHAR（與 V1 一致）
2. **User Entity**：使用 `status` Enum (UserStatus)
3. **Validation**：密碼只需 `(?=.*[a-z])(?=.*[A-Z])(?=.*\d)`，不需特殊字元

---

## 任務執行順序

```
Task 1: User 基礎建設 ─────┐
                          ├──→ Task 2: DTO 建立 ──→ Task 3: Service ──→ Task 5: Unit Test
Task 4: Admin API ─────────┤                                    │
                          │                                    ▼
                          └─────────────────────────────→ Task 6: Integration Test
```

**建議批次選擇**（可一次選擇多個連續任務）：
1. **基礎批次**: Task 1 + Task 2
2. **核心批次**: Task 3 + Task 4
3. **測試批次**: Task 5 + Task 6

---

*計畫建立日期: 2026-08-27*
