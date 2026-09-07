# Authentication & Authorization Module - Architecture

## 1. 架構目標與約束

### 1.1 設計目標

| 目標 | 說明 |
|------|------|
| **無狀態認證** | 使用 JWT 實現 Stateless Authentication，無需伺服器端 Session 儲存 |
| **安全性** | Token 黑名單、Rate Limiting、密碼學安全的簽章驗證 |
| **可擴展性** | 支援未來新增 Refresh Token、OAuth2 等機制 |
| **效能優化** | 先查黑名單再驗簽章，減少不必要的密碼學運算 |
| **一致性** | 遵循現有 `common` 模組的設計模式與命名慣例 |

### 1.2 技術約束

| 約束 | 依據 |
|------|------|
| Framework | Java 21 + Spring Boot 4.1.1 |
| 認證方式 | Stateless JWT（jjwt library） |
| Session Policy | `SessionCreationPolicy.STATELESS` |
| 快取/黑名單 | Spring Data Redis |
| Rate Limiting | Redis + Lua Script（原子性） |
| API 前綴 | `/api/v1/` |

### 1.3 既有元件復用

| 元件 | 位置 | 用途 |
|------|------|------|
| `SecurityConfig` | `common/config/SecurityConfig.java` | 需擴展 JWT Filter |
| `CurrentUser` | `common/security/CurrentUser.java` | 已具備 userId, email, role |
| `Role` | `common/domain/enums/Role.java` | CUSTOMER / AGENT / ADMIN |
| `ForbiddenOperationException` | `common/exception/ForbiddenOperationException.java` | 授權失敗時拋出 |

### 1.4 禁止事項

- 不得信任前端傳入的過期時間參數（如 `X-Token-Expiration-Time` Header）
- 不得在 JWT Claims 中放入密碼或敏感資料
- 不得繞過 Bean Validation 進行輸入驗證
- 不得在非 Transactional 方法中進行多表寫入操作
- 不得使用 String 拼接組裝 Rate Limit Key（需使用固定前綴）

---

## 2. 模組邊界與責任

### 2.1 模組位置

```
src/main/java/com/pk/support_ticket_api/
├── auth/
│   ├── web/
│   │   └── AuthController.java              # 登入、登出 API
│   ├── service/
│   │   ├── AuthService.java                  # 認證服務介面
│   │   ├── AuthServiceImpl.java              # 認證服務實作
│   │   ├── JwtService.java                    # JWT 產生/驗證
│   │   └── TokenBlacklistService.java        # Redis 黑名單管理
│   ├── filter/
│   │   ├── JwtAuthenticationFilter.java      # JWT 認證 Filter
│   │   └── RateLimitingFilter.java           # 頻率限制 Filter
│   ├── dto/
│   │   ├── LoginRequest.java                 # 登入請求
│   │   ├── LoginResponse.java                # 登入回應
│   │   └── ErrorResponse.java                # 錯誤回應
│   └── exception/
│       ├── AuthException.java                # 認證例外基底
│       ├── InvalidCredentialsException.java  # 帳密錯誤
│       ├── AccountDisabledException.java      # 帳號停用
│       ├── TokenExpiredException.java        # Token 過期
│       ├── TokenRevokedException.java        # Token 已撤銷
│       ├── InvalidTokenException.java        # 無效 Token
│       └── RateLimitExceededException.java  # 頻率超限
│
└── common/
    └── security/
        ├── CurrentUser.java                  # 目前登入者資訊
        └── CurrentUserAuditorAware.java      # JPA Auditor 整合
```

### 2.2 責任矩陣

| 層級 | 元件 | 責任 |
|------|------|------|
| **Web** | `AuthController` | HTTP 協商、路由、Request 驗證 |
| **Service** | `AuthService` | 登入/登出業務邏輯 |
| **Service** | `JwtService` | JWT 產生、驗證、Claims 解析 |
| **Service** | `TokenBlacklistService` | Redis 黑名單管理 |
| **Filter** | `JwtAuthenticationFilter` | 請求認證、SecurityContext 設定 |
| **Filter** | `RateLimitingFilter` | 頻率限制檢查 |
| **DTO** | `LoginRequest` | 登入請求驗證 |
| **DTO** | `LoginResponse` | 登入成功回應 |
| **Exception** | 各 AuthException 子類 | 認證錯誤類型區分 |

---

## 3. 依賴方向

### 3.1 依賴規則

```
┌─────────────────────────────────────────────────────────┐
│                      AuthController                      │
│               (依賴 AuthService、DTO、Exception)         │
└─────────────────────────┬───────────────────────────────┘
                          │ 依賴
┌─────────────────────────▼───────────────────────────────┐
│                       AuthService                         │
│           (依賴 JwtService、TokenBlacklistService、        │
│            UserRepository、PasswordEncoder)               │
└─────────────────────────┬───────────────────────────────┘
                          │ 依賴
┌─────────────────────────▼───────────────────────────────┐
│                     JwtService                           │
│              (依賴 RedisTemplate、Configuration)          │
└─────────────────────────────────────────────────────────┘
```

### 3.2 外部依賴

| 依賴 | 位置 | 用途 |
|------|------|------|
| `UserRepository` | `users/repository/UserRepository.java` | 驗證帳號、取得使用者資料 |
| `PasswordEncoder` | Spring Security | BCrypt 密碼比對 |
| `RedisTemplate` | Spring Data Redis | Token 黑名單、Rate Limit 計數 |
| `Role` | `common/domain/enums/Role.java` | 角色枚舉 |

### 3.3 被依賴關係

| 元件 | 被誰依賴 |
|------|----------|
| `JwtAuthenticationFilter` | `SecurityConfig`（需註冊為 Filter） |
| `RateLimitingFilter` | `SecurityConfig`（需註冊為 Filter） |
| `AuthException` 子類 | `ApiExceptionHandler`（統一例外處理） |
| `CurrentUser` | 所有需要取得目前登入者的 Service |

### 3.4 Filter 執行順序

```
Request → RateLimitingFilter (順序: 100) → JwtAuthenticationFilter (順序: 200) → AuthorizationFilter
```

> **順序原則**：Rate Limiting 應在 JWT 驗證之前執行，以過濾惡意請求並節省伺服器資源。

---

## 4. JWT Token 設計

### 4.1 Access Token 結構

```json
{
  "sub": "user-uuid",
  "email": "user@example.com",
  "role": "CUSTOMER",
  "iat": 1699999999,
  "exp": 1699999999 + 7200,
  "jti": "unique-token-id"
}
```

| Claim | 說明 | 安全性說明 |
|-------|------|------------|
| `sub` | 使用者 UUID | 唯一識別，不可偽造 |
| `email` | 使用者 Email | 用於顯示，僅供參考 |
| `role` | 角色 | **不作為授權唯一依據**，需在 Service 層二次驗證 |
| `iat` | 發發時間 | 防止回放攻擊 |
| `exp` | 過期時間 | 限制 Token 生命週期 |
| `jti` | Token ID | 用於黑名單追蹤 |

### 4.2 Claims 限制

| 可放入 | 不可放入 |
|--------|----------|
| userId (UUID) | password |
| email | 完整 User entity |
| role | 內部 ID |
| iat, exp, jti | 敏感資料 |

### 4.3 JWT 設定

```yaml
# application.yml
jwt:
  secret: ${JWT_SECRET}  # 必須 256 bits 以上
  access-expiration: 7200000  # 2 小時（毫秒）
```

---

## 5. 主要資料流程

### 5.1 登入流程

```
Client POST /api/v1/auth/login
        │
        ▼
┌─────────────────────────────────────────────────┐
│ RateLimitingFilter                              │
│  - 檢查 IP 是否超過 Rate Limit                   │
│  - 若逾限 → 回傳 429 Too Many Requests           │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│ AuthController.login()                          │
│  - @Valid LoginRequest                          │
│  - 呼叫 authService.login(request)              │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│ AuthServiceImpl.login()                          │
│  1. 查詢使用者（repository.findByEmail）        │
│  2. 若不存在 → InvalidCredentialsException       │
│  3. 檢查帳號狀態（status != ACTIVE）              │
│     → AccountDisabledException                  │
│  4. 密碼比對（passwordEncoder.matches）         │
│     → InvalidCredentialsException                │
│  5. 呼叫 jwtService.generateToken(user)         │
│  6. 回傳 LoginResponse                          │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
Response: 200 OK + LoginResponse
```

### 5.2 登出流程

```
Client POST /api/v1/auth/logout
        Header: Authorization: Bearer <token>
        │
        ▼
┌─────────────────────────────────────────────────┐
│ JwtAuthenticationFilter                         │
│  1. 解析 Bearer Token                           │
│  2. 檢查 Redis 黑名單（jwt:blk:{jti}）          │
│  3. 若在黑名單 → 401 TOKEN_REVOKED              │
│  4. 驗證 JWT 簽章                               │
│  5. 設定 SecurityContext                        │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│ AuthController.logout()                         │
│  - 從 SecurityContext 取得 CurrentUser          │
│  - 呼叫 authService.logout(token)               │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│ AuthServiceImpl.logout()                         │
│  1. 解析 Token 的 exp Claim（後端自行計算）        │
│  2. 計算剩餘有效期：remaining = exp - now        │
│  3. 呼叫 blacklistService.addToBlacklist(jti,    │
│     remaining)                                  │
│  4. 回傳成功訊息                               │
└─────────────────────────────────────────────────┘
                      │
                      ▼
Response: 200 OK + {"message": "已成功登出"}
```

> **⚠️ 安全性要點**
>
> 剩餘有效期必須由後端**自行解析 JWT 的 exp Claim 計算**，嚴禁信任前端傳入的參數。

### 5.3 JWT 驗證流程（請求認證）

```
Request → RateLimitingFilter → JwtAuthenticationFilter → Controller
```

**JwtAuthenticationFilter 執行順序（已優化）**：

```
1. 從 Header 提取 Authorization: Bearer <token>
2. 若無 Bearer Token → 跳過（交給後續 filter 處理 401）
3. 【優化】快速查詢 Redis 黑名單（jti 比對）
   └─ 若命中 → 回傳 401 TOKEN_REVOKED（節省簽章驗證）
4. 【優化】執行 JWT 密碼學簽章驗證
5. 從 Claims 建立 CurrentUser
6. 設定 SecurityContextHolder.getContext().setAuthentication()
```

> **💡 效能優化說明**
>
> 先查黑名單再驗簽章：
> - 黑名單中的 Token 為少數（約 1-5%）
> - 大部分合法請求：1 次 Redis 查詢（未命中）→ JWT 驗證
> - 黑名單 Token：1 次 Redis 查詢（命中）→ 直接阻擋

### 5.4 Rate Limiting 流程

```
Request → RateLimitingFilter
              │
              ▼
        檢查 Redis Key
        ratelimit:{endpoint}:{ip}
              │
              ├── < 限制 ──→ 計數 +1 → 放行
              │
              └── >= 限制 ──→ 回傳 429 + Retry-After
```

---

## 6. Redis 資料結構

### 6.1 Token 黑名單

| 項目 | 說明 |
|------|------|
| Key | `jwt:blk:{jti}` |
| Value | `"1"` |
| TTL | Token 剩餘有效期（秒） |

### 6.2 Rate Limit 計數器

| 項目 | 說明 |
|------|------|
| Key | `ratelimit:{endpoint}:{ip_or_user_id}` |
| Value | 計數 |
| TTL | 時間窗口秒數 |

### 6.3 Lua Script（原子性保證）

```lua
-- ratelimit.lua
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
if current > tonumber(ARGV[2]) then
    return 0  -- 逾限
end
return 1  -- 允許
```

---

## 7. 例外處理

### 7.1 例外類型

| Exception | HTTP Status | Error Code | 說明 |
|-----------|-------------|------------|------|
| `InvalidCredentialsException` | 401 | INVALID_CREDENTIALS | 帳密錯誤 |
| `AccountDisabledException` | 403 | ACCOUNT_DISABLED | 帳號停用 |
| `TokenExpiredException` | 401 | TOKEN_EXPIRED | Token 過期 |
| `TokenRevokedException` | 401 | TOKEN_REVOKED | Token 已撤銷 |
| `InvalidTokenException` | 401 | INVALID_TOKEN | 無效 Token |
| `RateLimitExceededException` | 429 | TOO_MANY_REQUESTS | 頻率超限 |

### 7.2 例外類層級

```java
// 基礎類別
public class AuthException extends RuntimeException {
    private final String errorCode;
    
    public AuthException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
}

// 具體例外
public class InvalidCredentialsException extends AuthException {
    public InvalidCredentialsException() {
        super("帳號或密碼錯誤", "INVALID_CREDENTIALS");
    }
}
```

### 7.3 ApiExceptionHandler 整合

```java
@ExceptionHandler(AuthException.class)
public ResponseEntity<ErrorResponse> handleAuthException(AuthException ex) {
    HttpStatus status = switch (ex) {
        case InvalidCredentialsException() -> HttpStatus.UNAUTHORIZED;
        case AccountDisabledException() -> HttpStatus.FORBIDDEN;
        case TokenExpiredException() -> HttpStatus.UNAUTHORIZED;
        case TokenRevokedException() -> HttpStatus.UNAUTHORIZED;
        case InvalidTokenException() -> HttpStatus.UNAUTHORIZED;
        case RateLimitExceededException r -> HttpStatus.TOO_MANY_REQUESTS;
        default -> HttpStatus.INTERNAL_SERVER_ERROR;
    };
    
    return ResponseEntity.status(status)
        .body(new ErrorResponse(ex.getErrorCode(), ex.getMessage()));
}
```

---

## 8. API 端點定義

### 8.1 端點總覽

| 方法 | 路徑 | 說明 | 認證需求 |
|------|------|------|----------|
| `POST` | `/api/v1/auth/login` | 登入 | 匿名 |
| `POST` | `/api/v1/auth/logout` | 登出 | 需登入 |

### 8.2 POST /api/v1/auth/login

**Request Body**:
```json
{
  "email": "user@example.com",
  "password": "SecurePass123!"
}
```

**Validation Rules**:
- `email`: 必填，有效 email 格式
- `password`: 必填，長度 8-100

**Success Response (200 OK)**:
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 7200
}
```

**Error Responses**:

| Status | 條件 | Response |
|--------|------|----------|
| 400 | 驗證失敗 | `{"errors": [...], "timestamp": "...", "traceId": "..."}` |
| 401 | 帳密錯誤 | `{"error": "INVALID_CREDENTIALS", "message": "帳號或密碼錯誤"}` |
| 403 | 帳號停用 | `{"error": "ACCOUNT_DISABLED", "message": "帳號已被停用"}` |
| 429 | Rate Limit | `{"error": "TOO_MANY_REQUESTS", "message": "請求頻率過高，請稍後再試", "retryAfter": 300}` |

### 8.3 POST /api/v1/auth/logout

**Request Header**:
```
Authorization: Bearer <access_token>
```

**Success Response (200 OK)**:
```json
{
  "message": "已成功登出"
}
```

**Error Responses**:

| Status | 條件 | Response |
|--------|------|----------|
| 401 | 未提供 Token | `{"error": "INVALID_TOKEN", "message": "無效的 Token 格式"}` |
| 401 | Token 已撤銷 | `{"error": "TOKEN_REVOKED", "message": "Token 已失效"}` |

---

## 9. SecurityConfig 設定

### 9.1 Filter 註冊順序

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RateLimitingFilter rateLimitingFilter,
            JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            
            // 設定 Filter 順序
            .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            
            // 授權規則
            .authorizeHttpRequests(auth -> auth
                // 允許匿名存取的端點
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/v1/auth/login").permitAll()
                // Admin 端點需要 ADMIN 角色
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                // 其他所有端點需要登入
                .anyRequest().authenticated()
            );
        
        return http.build();
    }
}
```

### 9.2 端點訪問矩陣

| 方法 | 路徑 | 認證需求 | 角色需求 |
|------|------|----------|----------|
| `GET` | `/actuator/health` | 匿名 | - |
| `POST` | `/api/v1/auth/login` | 匿名 | - |
| `POST` | `/api/v1/auth/logout` | 需登入 | - |
| `/api/v1/**` | 其他所有端點 | 需登入 | - |
| `/api/v1/admin/**` | Admin 端點 | 需登入 | ADMIN |

---

## 10. 元件介面設計

### 10.1 AuthService

```java
public interface AuthService {
    
    /**
     * 使用者登入
     * @param request 登入請求（email + password）
     * @return 登入回應（accessToken + expiresIn）
     * @throws InvalidCredentialsException 帳密錯誤
     * @throws AccountDisabledException 帳號停用
     */
    LoginResponse login(LoginRequest request);
    
    /**
     * 使用者登出
     * @param token 當前使用的 JWT Token
     */
    void logout(String token);
    
    /**
     * 驗證 Token 是否有效（內部使用）
     * @param token JWT Token
     * @return 是否有效
     */
    boolean validateToken(String token);
}
```

### 10.2 JwtService

```java
public interface JwtService {
    
    /**
     * 產生 JWT Token
     * @param user 使用者實體
     * @return JWT Token 字串
     */
    String generateToken(User user);
    
    /**
     * 驗證 Token 簽章與過期
     * @param token JWT Token
     * @return 是否有效
     */
    boolean validateToken(String token);
    
    /**
     * 解析 Token 取得 CurrentUser
     * @param token JWT Token
     * @return CurrentUser
     */
    CurrentUser parseToken(String token);
    
    /**
     * 從 Token 提取 JTI（用於黑名單）
     * @param token JWT Token
     * @return JTI
     */
    String extractJti(String token);
    
    /**
     * 從 Token 提取剩餘有效期（秒）
     * @param token JWT Token
     * @return 剩餘秒數
     */
    long extractExpiration(String token);
}
```

### 10.3 TokenBlacklistService

```java
public interface TokenBlacklistService {
    
    /**
     * 將 Token 加入黑名單
     * @param jti Token ID
     * @param ttlSeconds 剩餘有效期（秒）
     */
    void addToBlacklist(String jti, long ttlSeconds);
    
    /**
     * 檢查 Token 是否在黑名單
     * @param jti Token ID
     * @return 是否在黑名單
     */
    boolean isBlacklisted(String jti);
}
```

### 10.4 RateLimitingFilter

```java
@Component
public class RateLimitingFilter extends OncePerRequestFilter {
    
    // 限制規則
    private static final Map<String, RateLimitRule> RULES = Map.of(
        "/api/v1/auth/login", new RateLimitRule(5, 15 * 60),  // 5 次/15 分鐘
        "/**", new RateLimitRule(100, 60)                       // 100 次/分鐘
    );
    
    // 使用的 Lua Script（確保原子性）
    private static final String RATE_LIMIT_SCRIPT = """
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
            redis.call('EXPIRE', KEYS[1], ARGV[1])
        end
        if current > tonumber(ARGV[2]) then
            return 0
        end
        return 1
        """;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        
        String endpoint = request.getRequestURI();
        String clientIp = getClientIp(request);
        String key = "ratelimit:" + endpoint + ":" + clientIp;
        
        RateLimitRule rule = getMatchingRule(endpoint);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }
        
        Boolean allowed = redisTemplate.execute(
            EVALUATE_SCRIPT,
            Collections.singletonList(key),
            String.valueOf(rule.windowSeconds()),
            String.valueOf(rule.maxRequests())
        );
        
        if (Boolean.FALSE.equals(allowed)) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(rule.windowSeconds()));
            response.setContentType("application/json");
            response.getWriter().write("""
                {"error":"TOO_MANY_REQUESTS","message":"請求頻率過高，請稍後再試","retryAfter":%d}
                """.formatted(rule.windowSeconds()));
            return;
        }
        
        filterChain.doFilter(request, response);
    }
}
```

---

## 11. 測試策略

### 11.1 Unit Test — JwtService

**測試檔案**: `src/test/java/com/pk/support_ticket_api/auth/service/JwtServiceTest.java`

| 測試案例 | 測試內容 |
|----------|----------|
| `generateToken_createsValidToken` | 產生有效 Token，內容包含正確 Claims |
| `validateToken_validToken_returnsTrue` | 有效 Token 驗證成功 |
| `validateToken_expiredToken_returnsFalse` | 過期 Token 驗證失敗 |
| `validateToken_invalidSignature_returnsFalse` | 錯誤簽章驗證失敗 |
| `parseToken_extractsClaimsCorrectly` | 解析 Token 取得正確的 CurrentUser |
| `extractJti_returnsCorrectJti` | 正確提取 JTI |
| `extractExpiration_calculatesRemainingSeconds` | 正確計算剩餘有效期 |

### 11.3 Unit Test — TokenBlacklistService

**測試檔案**: `src/test/java/com/pk/support_ticket_api/auth/service/TokenBlacklistServiceTest.java`

| 測試案例 | 測試內容 |
|----------|----------|
| `addToBlacklist_setsKeyWithTTL` | 加入黑名單後 Redis 有正確的 Key 和 TTL |
| `isBlacklisted_newToken_returnsFalse` | 新 Token 不在黑名單 |
| `isBlacklisted_revokedToken_returnsTrue` | 已撤銷 Token 在黑名單 |
| `isBlacklisted_expiredBlacklist_returnsFalse` | TTL 過期後自動移除 |

### 11.4 Unit Test — RateLimitingFilter

**測試檔案**: `src/test/java/com/pk/support_ticket_api/auth/filter/RateLimitingFilterTest.java`

| 測試案例 | 測試內容 |
|----------|----------|
| `doFilter_firstRequest_allowsThrough` | 首次請求允許通過 |
| `doFilter_underLimit_allowsThrough` | 限制內請求允許通過 |
| `doFilter_exceedLimit_returns429` | 超過限制回傳 429 |
| `doFilter_differentEndpoint_resetsCount` | 不同端點獨立計數 |
| `doFilter_healthEndpoint_bypassesCheck` | /actuator/health 不受限制 |

---

## 12. 環境變數

### 12.1 必要變數

| 變數名 | 說明 | 範例 |
|--------|------|------|
| `JWT_SECRET` | JWT 簽章金鑰（至少 256 bits） | `yoursecretkey...` |
| `JWT_ACCESS_EXPIRATION` | Access Token 有效期（毫秒） | `7200000` (2h) |

### 12.2 Redis 變數（既有）

| 變數名 | 說明 | 預設值 |
|--------|------|--------|
| `REDIS_HOST` | Redis 主機 | localhost |
| `REDIS_PORT` | Redis 連接埠 | 6379 |

### 12.3 安全原則

- JWT_SECRET 必須使用高強度隨機值
- 不可提交到 Git（已於 `.env.example.env` 排除）
- 建議長度：64 字元以上

---

## 13. 實作拆分建議

### Phase 1: 基礎建設（預計 1 天）

| 工作項目 | 說明 |
|----------|------|
| 新增 jjwt dependency | JWT 處理函式庫 |
| 建立 AuthException 體系 | 各種認證例外 |
| 建立 JwtService | Token 產生與驗證 |
| 建立 TokenBlacklistService | Redis 黑名單管理 |

**預計產出**:
```
src/main/java/com/pk/support_ticket_api/auth/
├── exception/
│   ├── AuthException.java
│   ├── InvalidCredentialsException.java
│   ├── AccountDisabledException.java
│   ├── TokenExpiredException.java
│   ├── TokenRevokedException.java
│   ├── InvalidTokenException.java
│   └── RateLimitExceededException.java
└── service/
    ├── JwtService.java
    └── TokenBlacklistService.java
```

### Phase 2: Filter 實作（預計 1 天）

| 工作項目 | 說明 |
|----------|------|
| 建立 JwtAuthenticationFilter | JWT 認證 Filter |
| 建立 RateLimitingFilter | 頻率限制 Filter |
| 更新 SecurityConfig | 註冊 Filter、設定授權規則 |

**預計產出**:
```
src/main/java/com/pk/support_ticket_api/auth/
├── filter/
│   ├── JwtAuthenticationFilter.java
│   └── RateLimitingFilter.java
```

### Phase 3: Auth API（預計 1 天）

| 工作項目 | 說明 |
|----------|------|
| 建立 DTOs | LoginRequest, LoginResponse |
| 建立 AuthService | 登入/登出邏輯 |
| 建立 AuthController | API 端點 |
| 整合 ApiExceptionHandler | 統一例外處理 |

**預計產出**:
```
src/main/java/com/pk/support_ticket_api/auth/
├── dto/
│   ├── LoginRequest.java
│   └── LoginResponse.java
├── service/
│   ├── AuthService.java
│   └── AuthServiceImpl.java
└── web/
    └── AuthController.java
```

### Phase 4: 測試（預計 1 天）

| 測試類型 | 測試項目 |
|----------|----------|
| Unit Test | JwtService、TokenBlacklistService |
| Unit Test | RateLimitingFilter |

**預計產出**:
```
src/test/java/com/pk/support_ticket_api/auth/
├── service/
│   ├── JwtServiceTest.java
│   ├── TokenBlacklistServiceTest.java
│   └── AuthServiceTest.java
└── filter/
    └── RateLimitingFilterTest.java
```

---

## 14. 驗收標準

### Phase 1 驗收標準

| ID | 標準 | 測試方式 |
|----|------|----------|
| AC-AUTH-01 | POST /api/v1/auth/login 正確帳密可取得 Access Token | Unit Test |
| AC-AUTH-02 | 錯誤帳密回傳 401 INVALID_CREDENTIALS | Unit Test |
| AC-AUTH-03 | 停用帳號登入回傳 403 ACCOUNT_DISABLED | Unit Test |
| AC-AUTH-04 | Access Token 可用於存取受保護端點 | Unit Test |
| AC-AUTH-05 | 過期 Access Token 回傳 401 | Unit Test |
| AC-AUTH-06 | POST /api/v1/auth/logout 可使 Token 失效 | Unit Test |
| AC-AUTH-07 | 登出後原 Token 無法使用 | Unit Test |
| AC-AUTH-08 | Rate Limit 逾限回傳 429 | Unit Test |
| AC-AUTH-09 | /actuator/health 無需認證即可存取 | Unit Test |

---

## 15. 尚未定義事項

| 項目 | 問題 | 建議選項 |
|------|------|----------|
| Agent 範圍限制 | Agent 是否需依部門/範圍限制可操作工單？ | A: 不限制 / B: 依部門限制 |
| Token 撤銷策略 | 除了手動 Logout，是否需要其他自動撤銷機制？ | A: 只有手動撤銷 / B: 密碼變更自動撤銷 |

---

## 16. 附錄

### 16.1 檔案清單

| 檔案 | 位置 | 說明 |
|------|------|------|
| SecurityConfig | `common/config/SecurityConfig.java` | Security 設定，需擴展 |
| CurrentUser | `common/security/CurrentUser.java` | 目前登入者資訊 |
| UserRepository | `users/repository/UserRepository.java` | 需新增 findByEmail |
| ApiExceptionHandler | `common/exception/ApiExceptionHandler.java` | 統一例外處理 |

### 16.2 參考文件

| 文件 | 位置 |
|------|------|
| 專案概覽 | `doc/overview.md` |
| Auth 需求文件 | `doc/requirements/auth.md` |
| User 架構文件 | `doc/architecture/user.md` |
| JJWT Library | https://github.com/jwtk/jjwt |

---

*文件版本: 1.0*
*建立日期: 2026-08-31*
*最後更新: 2026-08-31*
