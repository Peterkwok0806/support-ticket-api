# Authentication & Authorization 模組實作任務計畫

## 概述

根據 `doc/requirements/auth.md` 及 `doc/architecture/auth.md` 的實作拆分建議，將 Auth 模組分為以下獨立任務。

---

## Task 1: Auth 基礎建設 - Exception 體系

### 描述
建立認證相關的例外類別體系，統一管理所有認證錯誤。

### In scope
- 建立 `AuthException` 基底類別
- 建立 `InvalidCredentialsException` - 帳密錯誤
- 建立 `AccountDisabledException` - 帳號停用
- 建立 `TokenExpiredException` - Token 過期
- 建立 `TokenRevokedException` - Token 已撤銷
- 建立 `InvalidTokenException` - 無效 Token
- 建立 `RateLimitExceededException` - 頻率超限
- 更新 `ApiExceptionHandler` 處理這些例外

### Out of scope
- JwtService 實作
- TokenBlacklistService 實作
- Filter 實作
- Controller 實作

### Expected files
```
src/main/java/com/pk/support_ticket_api/auth/
└── exception/
    ├── AuthException.java
    ├── InvalidCredentialsException.java
    ├── AccountDisabledException.java
    ├── TokenExpiredException.java
    ├── TokenRevokedException.java
    ├── InvalidTokenException.java
    └── RateLimitExceededException.java
```

### Dependencies
- `common/exception/ApiExceptionHandler` - 已存在，需更新
- Spring Framework

### Acceptance criteria
- [x] `AuthException` 包含 errorCode 欄位
- [x] 各子類別正確繼承並設定對應的 errorCode
- [x] `ApiExceptionHandler` 可正確映射例外到 HTTP Status
- [x] `RateLimitExceededException` 包含 retryAfter 欄位
- [x] 所有例外皆可被正確序列化為 JSON 回應

### 風險與待確認
1. **Error Response 格式**：需確認與現有 ErrorResponse 格式一致

---

## Task 2: JwtService 實作

### 描述
建立 JWT 產生、驗證、解析服務。

### In scope
- 新增 jjwt library 依賴
- 建立 `JwtService` interface
- 建立 `JwtServiceImpl` 實作
- 實作 Token 產生（包含 jti、iat、exp）
- 實作 Token 驗證（簽章、過期）
- 實作 Token 解析（提取 Claims）
- 實作 TTL 計算（extractExpiration）

### Out of scope
- TokenBlacklistService
- Filter 實作
- AuthController

### Expected files
```
pom.xml
src/main/java/com/pk/support_ticket_api/auth/
└── service/
    ├── JwtService.java
    └── JwtServiceImpl.java
```

### Dependencies
- Task 1: AuthException 體系
- `User` Entity - 已存在
- `CurrentUser` - 已存在
- `Role` enum - 已存在

### Acceptance criteria
- [x] `generateToken(User)` 產生包含 sub、email、role、iat、exp、jti 的 Token
- [x] `validateToken(String)` 正確驗證有效 Token（回傳 true）
- [x] `validateToken(String)` 正確拒絕過期 Token（拋出 TokenExpiredException）
- [x] `validateToken(String)` 正確拒絕無效簽章（拋出 InvalidTokenException）
- [x] `parseToken(String)` 正確解析並回傳 CurrentUser
- [x] `extractJti(String)` 正確提取 jti
- [x] `extractExpiration(String)` 正確計算剩餘有效期（秒）
- [x] Token 使用 HS256 簽章

### 風險與待確認
1. **JWT_SECRET 管理**：需確認環境變數注入方式
2. **jti 生成**：使用 UUID 或 SecureRandom

---

## Task 3: TokenBlacklistService 實作

### 描述
建立 Redis Token 黑名單管理服務。

### In scope
- 建立 `TokenBlacklistService` interface
- 建立 `TokenBlacklistServiceImpl` 實作
- 實作加入黑名單（SETEX with TTL）
- 實作查詢黑名單（EXISTS）
- Redis TTL 自動過期

### Out of scope
- JwtService
- JwtAuthenticationFilter

### Expected files
```
src/main/java/com/pk/support_ticket_api/auth/
└── service/
    ├── TokenBlacklistService.java
    └── TokenBlacklistServiceImpl.java
```

### Dependencies
- Task 1: AuthException 體系
- Spring Data Redis - 專案已有
- `RedisTemplate` - 需注入

### Acceptance criteria
- [x] `addToBlacklist(jti, ttlSeconds)` 正確設定 Redis Key
- [x] `addToBlacklist` 使用 `jwt:blk:{jti}` 作為 Key
- [x] `addToBlacklist` 設定正確的 TTL
- [x] `isBlacklisted(jti)` 回傳 true 當 Token 在黑名單
- [x] `isBlacklisted(jti)` 回傳 false 當 Token 不在黑名單
- [x] 黑名單 Key 會在 TTL 過期後自動刪除

### 風險與待確認
1. **Redis 連線配置**：確認 application.yml 中的 Redis 設定
2. **TTL 計算**：確保剩余秒數計算正確

---

## Task 4: Filter 實作 - JwtAuthenticationFilter

### 描述
建立 JWT 認證 Filter，解析並驗證請求中的 JWT Token。

### In scope
- 建立 `JwtAuthenticationFilter`
- 實作請求 Filter 順序（order = 200）
- 實作 Bearer Token 解析
- 實作先查黑名單再驗簽章（優化順序）
- 實作 SecurityContext 設定
- 實作跳過邏輯（無 Token 時）

### Out of scope
- RateLimitingFilter
- SecurityConfig 更新

### Expected files
```
src/main/java/com/pk/support_ticket_api/auth/
└── filter/
    └── JwtAuthenticationFilter.java
```

### Dependencies
- Task 2: JwtService
- Task 3: TokenBlacklistService
- `CurrentUser` - 已存在
- Spring Security Filter 相關類別

### Acceptance criteria
- [x] Filter 繼承 `OncePerRequestFilter`
- [x] 從 Authorization Header 正確解析 Bearer Token
- [x] 無 Bearer Token 時跳過（交給後續 Filter 處理）
- [x] 先查 Redis 黑名單，再執行 JWT 簽章驗證
- [x] 黑名單 Token 直接回傳 401 TOKEN_REVOKED
- [x] 有效 Token 正確設定 SecurityContext
- [x] `CurrentUser` 正確傳遞到 SecurityContext
- [x] 各種例外正確映射到對應的 HTTP Status

### 風險與待確認
1. **Filter 順序**：需確認與 RateLimitingFilter 的順序關係
2. **例外處理**：需確認 Filter 層級例外不會影響全域例外處理

---

## Task 5: Filter 實作 - RateLimitingFilter

### 描述
建立 Rate Limiting Filter，限制請求頻率。

### In scope
- 建立 `RateLimitingFilter`
- 實作 Lua Script（原子性保證）
- 實作登入端點限制（5 次/15 分鐘）
- 實作一般端點限制（100 次/1 分鐘）
- 實作不同 IP/端點獨立計數
- 實作逾限回應（429 + Retry-After）

### Out of scope
- JwtAuthenticationFilter
- SecurityConfig 更新

### Expected files
```
src/main/java/com/pk/support_ticket_api/auth/
└── filter/
    └── RateLimitingFilter.java
```

### Dependencies
- Task 1: RateLimitExceededException
- Spring Data Redis - 專案已有
- `RedisTemplate` - 需注入

### Acceptance criteria
- [x] Filter 繼承 `OncePerRequestFilter`
- [x] `/api/v1/auth/login` 限制為 5 次/15 分鐘
- [x] 其他端點限制為 100 次/1 分鐘
- [x] `/actuator/health` 不受限制
- [x] 使用 Lua Script 確保計數原子性
- [x] 逾限回傳 429 狀態碼
- [x] 回傳 `Retry-After` Header
- [x] 不同 IP 獨立計數

### 風險與待確認
1. **Lua Script 錯誤處理**：需確認 Script 執行失敗時的處理
2. **Redis 連線失敗**：需確認是否 fallback 或阻擋請求

---

## Task 6: SecurityConfig 更新

### 描述
更新 SecurityConfig，註冊所有 Filter 並設定授權規則。

### In scope
- 註冊 RateLimitingFilter
- 註冊 JwtAuthenticationFilter
- 設定 Filter 順序
- 設定端點訪問矩陣
- 設定 ADMIN 角色限制

### Out of scope
- Filter 實作
- AuthController 實作

### Expected files
```
src/main/java/com/pk/support_ticket_api/common/
└── config/
    └── SecurityConfig.java (更新)
```

### Dependencies
- Task 4: JwtAuthenticationFilter
- Task 5: RateLimitingFilter
- `SecurityConfig` - 已存在，需更新

### Acceptance criteria
- [x] RateLimitingFilter 在 JwtAuthenticationFilter 之前執行
- [x] `/actuator/health` 允許匿名存取
- [x] `/api/v1/auth/login` 允許匿名存取
- [x] `/api/v1/admin/**` 需要 ADMIN 角色
- [x] 其他 `/api/v1/**` 需要已登入（任何角色）
- [x] CSRF 保持停用
- [x] Session 保持 STATELESS

### 風險與待確認
1. **Filter 順序**：RateLimitingFilter (100) → JwtAuthenticationFilter (200) → AuthorizationFilter
2. **URL 匹配方式**：需確認使用 `requestMatchers` 或 `mvcMatchers`

---

## Task 7: Auth API - DTO 與 Service

### 描述
建立 Auth Controller 所需的 DTO 和 Service 層。

### In scope
- 建立 `LoginRequest` record
- 建立 `LoginResponse` record
- 建立 `LogoutResponse` record
- 建立 `AuthService` interface
- 建立 `AuthServiceImpl` 實作
- 實作登入邏輯（驗證帳密、發放 Token）
- 實作登出邏輯（加入黑名單）

### Out of scope
- Filter 實作
- Controller 實作

### Expected files
```
src/main/java/com/pk/support_ticket_api/auth/
├── dto/
│   ├── LoginRequest.java
│   ├── LoginResponse.java
│   └── LogoutResponse.java
└── service/
    ├── AuthService.java
    └── AuthServiceImpl.java
```

### Dependencies
- Task 1: AuthException 體系
- Task 2: JwtService
- Task 3: TokenBlacklistService
- `UserRepository` - 已存在，需新增 findByEmail
- `PasswordEncoder` - Spring Security 內建

### Acceptance criteria
- [ ] `LoginRequest` 包含 email、password 欄位
- [ ] `LoginRequest` 有 Bean Validation（@Email、@NotBlank）
- [ ] `LoginResponse` 包含 accessToken、tokenType、expiresIn
- [ ] `AuthService.login()` 正確驗證帳密
- [ ] `AuthService.login()` 檢查帳號狀態（status != ACTIVE → AccountDisabledException）
- [ ] `AuthService.login()` 使用 PasswordEncoder 比對密碼
- [ ] `AuthService.login()` 產生 JWT Token
- [ ] `AuthService.logout()` 解析 Token 的 exp Claim
- [ ] `AuthService.logout()` 計算剩餘有效期
- [ ] `AuthService.logout()` 呼叫 blacklistService.addToBlacklist
- [ ] `AuthService.logout()` 不信任前端傳入的過期時間

### 風險與待確認
1. **UserRepository.findByEmail()**：需確認是否已存在，或需新增
2. **PasswordEncoder Bean**：需確認是否已設定

---

## Task 8: Auth API - Controller

### 描述
建立 AuthController，實作登入/登出 API 端點。

### In scope
- 建立 `AuthController`
- 實作 `POST /api/v1/auth/login`
- 實作 `POST /api/v1/auth/logout`
- API 文件標註（@Operation、@ApiResponse）

### Out of scope
- Filter 實作
- Service 實作

### Expected files
```
src/main/java/com/pk/support_ticket_api/auth/
└── web/
    └── AuthController.java
```

### Dependencies
- Task 7: DTO 和 AuthService
- `CurrentUser` - 已存在，需從 SecurityContext 取得

### Acceptance criteria
- [ ] `POST /api/v1/auth/login` - 接收 LoginRequest，回傳 LoginResponse
- [ ] `POST /api/v1/auth/logout` - 從 SecurityContext 取得 CurrentUser
- [ ] `POST /api/v1/auth/logout` - 從 Authorization Header 取得 Token
- [ ] 所有端點有 @Operation 標註
- [ ] 所有端點有 @ApiResponse 標註
- [ ] Swagger UI 可看到 API 文件

### 風險與待確認
1. **取得 Token 方式**：從 Header 或從 SecurityContext
2. **API 前綴**：使用 `/api/v1/auth` 或 `/api/auth`

---

## Task 9: 單元測試 - JwtService

### 描述
建立 JwtService 的單元測試。

### In scope
- 建立 `JwtServiceTest` 測試類
- 測試 Token 產生
- 測試 Token 驗證
- 測試 Token 解析

### Out of scope
- Integration Test
- Controller Test

### Expected files
```
src/test/java/com/pk/support_ticket_api/auth/
└── service/
    └── JwtServiceTest.java
```

### Dependencies
- Task 2: JwtService 實作
- Mockito - 專案已有

### Acceptance criteria
- [ ] `generateToken_createsValidToken` - 產生有效 Token
- [ ] `validateToken_validToken_returnsTrue` - 有效 Token 驗證成功
- [ ] `validateToken_expiredToken_returnsFalse` - 過期 Token 驗證失敗
- [ ] `validateToken_invalidSignature_returnsFalse` - 錯誤簽章驗證失敗
- [ ] `parseToken_extractsClaimsCorrectly` - 解析 Claims 正確
- [ ] `extractJti_returnsCorrectJti` - 提取 JTI 正確
- [ ] `extractExpiration_calculatesRemainingSeconds` - 計算剩餘時間正確

### 風險與待確認
- **測試隔離**：每個測試需產生獨立的 JWT_SECRET

---

## Task 10: 單元測試 - TokenBlacklistService

### 描述
建立 TokenBlacklistService 的單元測試。

### In scope
- 建立 `TokenBlacklistServiceTest` 測試類
- 測試加入黑名單
- 測試查詢黑名單
- 測試 TTL 過期

### Out of scope
- Integration Test

### Expected files
```
src/test/java/com/pk/support_ticket_api/auth/
└── service/
    └── TokenBlacklistServiceTest.java
```

### Dependencies
- Task 3: TokenBlacklistService 實作
- Embedded Redis 或 Mockito

### Acceptance criteria
- [ ] `addToBlacklist_setsKeyWithTTL` - 加入黑名單設定正確
- [ ] `isBlacklisted_newToken_returnsFalse` - 新 Token 不在黑名單
- [ ] `isBlacklisted_revokedToken_returnsTrue` - 已撤銷 Token 在黑名單
- [ ] `isBlacklisted_expiredBlacklist_returnsFalse` - TTL 過期後不在黑名單

### 風險與待確認
1. **測試 Redis**：使用 Embedded Redis 或 Mock RedisTemplate

---

## Task 11: 單元測試 - RateLimitingFilter

### 描述
建立 RateLimitingFilter 的單元測試。

### In scope
- 建立 `RateLimitingFilterTest` 測試類
- 測試首次請求允許
- 測試限制內請求允許
- 測試逾限請求阻擋

### Out of scope
- Integration Test

### Expected files
```
src/test/java/com/pk/support_ticket_api/auth/
└── filter/
    └── RateLimitingFilterTest.java
```

### Dependencies
- Task 5: RateLimitingFilter 實作
- Mockito

### Acceptance criteria
- [ ] `doFilter_firstRequest_allowsThrough` - 首次請求通過
- [ ] `doFilter_underLimit_allowsThrough` - 限制內通過
- [ ] `doFilter_exceedLimit_returns429` - 逾限回傳 429
- [ ] `doFilter_differentEndpoint_resetsCount` - 不同端點獨立計數
- [ ] `doFilter_healthEndpoint_bypassesCheck` - health 端點不受限

### 風險與待確認
1. **Mock Redis**：使用 Mockito Mock RedisTemplate.execute()

---

---

## 已確認決策

| # | 項目 | 決策 | 原因 |
|---|------|------|------|
| 1 | Refresh Token | 不實作 | 簡化實作，減少攻擊面 |
| 2 | Access Token 有效期 | 2 小時（7200 秒） | 平衡安全性與使用者體驗 |
| 3 | Token 撤銷 | Redis 黑名單 + TTL | 自動清理，無需 GC |
| 4 | Rate Limit 算法 | Sliding Window + Lua Script | 原子性保證，避免 Race Condition |
| 5 | Filter 順序 | RateLimitingFilter → JwtAuthenticationFilter | 先過濾惡意請求 |
| 6 | 黑名單查詢順序 | 先查黑名單再驗簽章 | 效能優化（黑名單 Token 少） |
| 7 | Logout TTL 計算 | 後端自行解析 JWT exp | 防止前端參數操控 |

### 對計畫的影響

1. **無 Refresh Token 實作**：直接跳過相關任務
2. **後端計算 TTL**：Logout 時需解析 Token 的 exp Claim
3. **Lua Script 原子性**：RateLimitingFilter 需使用 Redis Script

---

## 任務執行順序

```
Task 1: Exception 體系 ─────────────────────────────────────────┐
                                                              │
Task 2: JwtService ─────────────────────────────────────┐       │
                                                          │       │
Task 3: TokenBlacklistService ──────────────────────┐    │       │
                                                  │    │       │
Task 4: JwtAuthenticationFilter ──────────────┐    │    │       │
                                              │    │    │       │
Task 5: RateLimitingFilter ─────────────────┐  │    │    │       │
                                            │  │    │    │       │
Task 6: SecurityConfig 更新 ───────────────┐ │  │    │    │       │
                                          │ │  │    │    │       │
Task 7: DTO + AuthService ──────────────┐  │ │  │    │    │       │
                                        │  │ │  │    │    │       │
Task 8: AuthController ───────────────┐  │  │ │  │    │    │       │
                                      │  │  │ │  │    │    │       │
                                      ▼  ▼  ▼  ▼    ▼    ▼       ▼
                              Task 9: JwtServiceTest
                              Task 10: TokenBlacklistServiceTest
                              Task 11: RateLimitingFilterTest
```

**建議批次選擇**（可一次選擇多個連續任務）：
1. **基礎批次**: Task 1 + Task 2 + Task 3（核心服務）
2. **Filter 批次**: Task 4 + Task 5 + Task 6（安全過濾器）
3. **API 批次**: Task 7 + Task 8（認證 API）
4. **測試批次**: Task 9 + Task 10 + Task 11

---

## 額外前置任務

### Task A: 新增 jjwt 依賴

**需修改 `pom.xml`**：
```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

### Task B: 更新 .env.example.env

**需新增環境變數**：
```
JWT_SECRET=your-256-bit-secret-key-here-must-be-at-least-64-characters-long
JWT_ACCESS_EXPIRATION=7200000
```

---

*計畫建立日期: 2026-08-31*
