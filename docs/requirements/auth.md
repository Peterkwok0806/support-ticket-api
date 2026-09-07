# Authentication & Authorization Module - 功能需求規格書

## 1. 模組概述

### 1.1 目的
建立完整的 **JWT Authentication 與授權機制**，支援無狀態的身份驗證、角色型存取控制、以及資源層級的物件授權檢查。

### 1.2 技術上下文
- **Framework**: Java 21 + Spring Boot 4.1.1
- **認證方式**: Stateless JWT（jjwt library）
- **Session**: 無狀態（`SessionCreationPolicy.STATELESS`）
- **快取/黑名單**: Spring Data Redis
- **Rate Limiting**: Redis + 自定義 Filter
- **API 前綴**: `/api/v1/`

### 1.3 既有元件復用
| 元件 | 位置 | 用途 |
|------|------|------|
| `SecurityConfig` | `common/config/SecurityConfig.java` | 需擴展 JWT Filter |
| `CurrentUser` | `common/security/CurrentUser.java` | 已具備 userId, email, role |
| `Role` | `common/domain/enums/Role.java` | CUSTOMER / AGENT / ADMIN |
| `ForbiddenOperationException` | `common/exception/ForbiddenOperationException.java` | 授權失敗時拋出 |

---

## 2. 安全設定

### 2.1 Session Policy
```
SessionCreationPolicy.STATELESS
```

### 2.2 端點訪問矩陣

| 方法 | 路徑 | 認證需求 | 角色需求 |
|------|------|----------|----------|
| `POST` | `/api/v1/auth/login` | 匿名 | - |
| `POST` | `/api/v1/auth/logout` | 需登入 | - |
| `GET` | `/actuator/health` | 匿名 | - |
| `/api/v1/**` | 其他所有端點 | 需登入 | - |
| `/api/v1/admin/**` | Admin 端點 | 需登入 | ADMIN |

---

## 3. Phase 1: 認證機制

### 3.1 實作範圍

| 工作項目 | 說明 |
|----------|------|
| JWT Filter | 解析、驗證、設置 SecurityContext |
| Login API | 驗證帳密、核發 Token |
| Logout API | 將 Token 加入黑名單 |
| Rate Limiting | 限制登入請求頻率 |
| Redis 整合 | Token 黑名單、Rate Limit 計數器 |

### 3.2 JWT Token 設計

#### 3.2.1 Access Token
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

| Claim | 說明 |
|-------|------|
| `sub` | 使用者 UUID |
| `email` | 使用者 Email（用於顯示） |
| `role` | 角色（不作為授權唯一依據） |
| `iat` | 發發時間 |
| `exp` | 過期時間（預設 2 小時，建議 2-4 小時） |
| `jti` | Token ID（用於黑名單） |

#### 3.2.2 JWT Claims 限制
- **不可放入**: password、完整 User entity、內部 ID、敏感資料
- **可放入**: userId (UUID)、email、role

### 3.3 Login API

#### POST /api/v1/auth/login

**Request Body**:
```json
{
  "email": "user@example.com",
  "password": "SecurePass123!"
}
```

**Success Response (200 OK)**:
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 7200
}
```

| 欄位 | 說明 |
|------|------|
| `accessToken` | JWT Access Token |
| `tokenType` | 固定值 `Bearer` |
| `expiresIn` | Token 有效期（秒），預設 7200（2 小時） |

**Error Responses**:
| Status | 條件 | Response |
|--------|------|----------|
| 401 | 帳密錯誤 | `{"error": "INVALID_CREDENTIALS", "message": "帳號或密碼錯誤"}` |
| 403 | 帳號停用 | `{"error": "ACCOUNT_DISABLED", "message": "帳號已被停用"}` |
| 429 | Rate Limit | `{"error": "TOO_MANY_REQUESTS", "message": "請稍後再試"}` |

### 3.4 Logout API

#### POST /api/v1/auth/logout

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

**行為**:
1. 從 Authorization Header 解析 Token
2. 由後端解析 JWT 的 `exp` Claim，計算剩餘有效期（`exp - now`）
3. 將 Token JTI 加入 Redis 黑名單（TTL = 剩餘有效期）
4. 回傳成功

> **⚠️ 安全性備註**
>
> TTL 必須由後端自行計算。**嚴禁信任前端傳入的過期時間參數**（例如 `X-Token-Expiration-Time` Header），否則攻擊者可透過操控該參數使黑名單過早失效，導致登出機制被繞過。

---

## 4. Rate Limiting

### 4.1 機制
- 使用 Redis 實現分散式計數器
- 每個端點獨立計數
- Sliding Window 演算法

> **⚠️ 實作注意**
>
> 必須使用 **Lua Script** 確保計數與過期的原子性（Atomicity），避免高併發下的 Race Condition 導致限制失效。

**Lua Script 範例（原子操作）**:
```lua
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
if current > tonumber(ARGV[2]) then
    return 0
end
return 1
```

### 4.2 限制規則

| 端點 | 限制 | 時間窗口 |
|------|------|----------|
| `/api/v1/auth/login` | 5 次 | 15 分鐘 |
| 其他 API | 100 次 | 1 分鐘 |

### 4.3 實現方式
```
Key: ratelimit:{endpoint}:{ip_or_user_id}
Value: 計數
TTL: 時間窗口秒數
```

### 4.4 逾限回應
```json
{
  "error": "TOO_MANY_REQUESTS",
  "message": "請求頻率過高，請稍後再試",
  "retryAfter": 300
}
```
Response Header: `Retry-After: 300`

---

## 5. Redis 黑名單設計

### 5.1 Token 黑名單
```
Key: jwt:blk:{jti}
Value: "1"
TTL: Token 剩餘有效期（秒）
```

### 5.2 驗證流程
1. 解析 Token，提取 JTI
2. 檢查 Redis 是否存在 `jwt:blk:{jti}`
3. 若存在，回傳 401 Unauthorized

---

## 6. JWT Filter 流程

```
Request → RateLimitingFilter → JwtAuthenticationFilter → AuthorizationFilter
```

### 6.1 JwtAuthenticationFilter

**實作順序（已優化）**:
1. 從 Header 提取 `Authorization: Bearer <token>`
2. 若無 Bearer Token，跳過（交給後續 filter 處理 401）
3. **[已優化]** 快速查詢 Redis 黑名單（僅用 Token 字串或 JTI 比對）
4. 若在黑名單，回傳 401 TOKEN_REVOKED
5. **[已優化]** 執行 JWT 密碼學簽章與過期驗證（僅在非黑名單後執行）
6. 從 Claims 建立 `CurrentUser`
7. 設定 `SecurityContextHolder.getContext().setAuthentication()`

> **💡 效能優化說明**
>
> 先查黑名單再驗簽章的優點：
> - 黑名單中的 Token 為少數（約 1-5%）
> - 大部分合法請求：1 次 Redis 查詢（未命中）→ 繼續 JWT 驗證
> - 黑名單 Token：1 次 Redis 查詢（命中）→ 直接阻擋（節省昂貴的密碼學運算）

### 6.2 Exception Handling
| 例外 | HTTP Status | Response |
|------|-------------|----------|
| Token 格式錯誤 | 401 | `{"error": "INVALID_TOKEN", "message": "無效的 Token 格式"}` |
| Token 過期 | 401 | `{"error": "TOKEN_EXPIRED", "message": "Token 已過期"}` |
| Token 已撤銷 | 401 | `{"error": "TOKEN_REVOKED", "message": "Token 已失效"}` |
| 簽章驗證失敗 | 401 | `{"error": "INVALID_TOKEN", "message": "Token 驗證失敗"}` |

---

## 7. Phase 2: 授權機制

### 7.1 實作範圍

| 工作項目 | 說明 |
|----------|------|
| @PreAuthorize 設定 | 角色層級授權 |
| Object-level Authorization | 資源所有權檢查 |
| Ticket 存取控制 | Owner / Agent / Admin 權限 |

### 7.2 角色層級授權

使用 `@PreAuthorize` 註解：

```java
@PreAuthorize("hasRole('ADMIN')")
// 自動轉換為 hasAuthority('ROLE_ADMIN')
```

### 7.3 Object-level Authorization

#### 7.3.1 原則
> **JWT role 正確 ≠ 授權存取該資源**
>
> 必須在 Service 層檢查資源所有權

#### 7.3.2 Ticket 存取矩陣

| 角色 | 自己的 Ticket | 他人的 Ticket | 所有 Ticket |
|------|--------------|---------------|-------------|
| CUSTOMER | Read, Update, Close | - | - |
| AGENT | - | Read, Update, Assign | Read, Update |
| ADMIN | - | - | Read, Update, Delete |

#### 7.3.3 實作模式

```java
// Service 層 - 必須檢查
@Service
public class TicketServiceImpl implements TicketService {

    public TicketResponse updateTicket(UUID ticketId, UpdateRequest request) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("工單不存在"));

        CurrentUser currentUser = getCurrentUser();

        // Object-level authorization
        if (!ticketAuthorization.canModify(currentUser, ticket)) {
            throw new ForbiddenOperationException("您無權修改此工單");
        }

        // 業務邏輯...
    }
}
```

#### 7.3.4 TicketAuthorization 介面

```java
public interface TicketAuthorization {

    /**
     * 檢查使用者是否有權讀取工單
     */
    boolean canRead(CurrentUser user, Ticket ticket);

    /**
     * 檢查使用者是否有權修改工單
     */
    boolean canModify(CurrentUser user, Ticket ticket);

    /**
     * 檢查使用者是否有權刪除工單
     */
    boolean canDelete(CurrentUser user, Ticket ticket);

    /**
     * 檢查 Agent 是否可操作該工單（依據部門/範圍）
     */
    boolean canAgentOperate(CurrentUser user, Ticket ticket);
}
```

#### 7.3.5 預設實作邏輯

```java
@Component
public class TicketAuthorizationService implements TicketAuthorization {

    @Override
    public boolean canRead(CurrentUser user, Ticket ticket) {
        return switch (Role.valueOf(user.role())) {
            case ADMIN -> true;
            case AGENT -> true;  // Agent 可讀取所有工單
            case CUSTOMER -> ticket.getCustomerId().equals(user.userId());
        };
    }

    @Override
    public boolean canModify(CurrentUser user, Ticket ticket) {
        return switch (Role.valueOf(user.role())) {
            case ADMIN -> true;
            case AGENT -> true;  // Agent 可修改所有工單
            case CUSTOMER -> ticket.getCustomerId().equals(user.userId())
                    && ticket.getStatus() != TicketStatus.CLOSED;
        };
    }

    @Override
    public boolean canDelete(CurrentUser user, Ticket ticket) {
        return Role.valueOf(user.role()) == Role.ADMIN;
    }

    @Override
    public boolean canAgentOperate(CurrentUser user, Ticket ticket) {
        // 未來可擴展：依據 Agent 負責部門限制
        return Role.valueOf(user.role()) == Role.AGENT;
    }
}
```

---

## 8. 環境變數

### 8.1 必要變數（需注入）

| 變數名 | 說明 | 範例 |
|--------|------|------|
| `JWT_SECRET` | JWT 簽章金鑰（至少 256 bits） | `yoursecretkey...` |
| `JWT_ACCESS_EXPIRATION` | Access Token 有效期（毫秒） | `7200000` (2h，建議 2-4 小時) |

### 8.2 Redis 變數（既有）

| 變數名 | 說明 | 預設值 |
|--------|------|--------|
| `REDIS_HOST` | Redis 主機 | localhost |
| `REDIS_PORT` | Redis 連接埠 | 6379 |

### 8.3 安全原則
- JWT_SECRET 必須使用高強度隨機值
- 不可提交到 Git（已於 `.env.example.env` 排除）
- 建議長度：64 字元以上

---

## 9. 專案結構

### 9.1 新增檔案

```
src/main/java/com/pk/support_ticket_api/
├── auth/
│   ├── web/
│   │   └── AuthController.java          # 登入、登出
│   ├── service/
│   │   ├── AuthService.java
│   │   ├── AuthServiceImpl.java
│   │   ├── JwtService.java              # JWT 產生/驗證
│   │   └── TokenBlacklistService.java   # Redis 黑名單
│   ├── filter/
│   │   ├── JwtAuthenticationFilter.java
│   │   └── RateLimitingFilter.java
│   ├── dto/
│   │   ├── LoginRequest.java
│   │   └── LoginResponse.java
│   └── exception/
│       └── AuthException.java
│
├── ticket/
│   ├── authorization/
│   │   ├── TicketAuthorization.java     # 介面
│   │   └── TicketAuthorizationService.java  # 預設實作
│   └── service/
│       └── TicketServiceImpl.java       # 需整合 authorization
```

### 9.2 修改檔案

| 檔案 | 修改內容 |
|------|----------|
| `SecurityConfig.java` | 加入 JWT Filter、Rate Limiting Filter |
| `pom.xml` | 新增 jjwt dependency |
| `application.yml` | JWT 設定、Rate Limit 設定 |
| `UserRepository.java` | 加入 `findByEmail()` 查詢 |
| `UserService.java` | 加入密碼驗證方法 |

---

## 10. 異常處理

### 10.1 Auth Exception 類型

| Exception | HTTP Status | Error Code |
|-----------|-------------|------------|
| `InvalidCredentialsException` | 401 | INVALID_CREDENTIALS |
| `AccountDisabledException` | 403 | ACCOUNT_DISABLED |
| `TokenExpiredException` | 401 | TOKEN_EXPIRED |
| `TokenRevokedException` | 401 | TOKEN_REVOKED |
| `InvalidTokenException` | 401 | INVALID_TOKEN |
| `RateLimitExceededException` | 429 | TOO_MANY_REQUESTS |

### 10.2 Error Response 格式

```json
{
  "error": "ERROR_CODE",
  "message": "人類可讀的錯誤訊息",
  "timestamp": "2026-08-31T10:00:00Z",
  "path": "/api/v1/auth/login"
}
```

---

## 11. 測試策略

### 11.1 Unit Tests

| 測試類 | 測試項目 |
|--------|----------|
| `JwtServiceTest` | Token 產生、驗證、Claims 解析 |
| `TokenBlacklistServiceTest` | 黑名單加入、查詢 |
| `RateLimitingFilterTest` | 計數邏輯、逾限阻擋 |
| `TicketAuthorizationServiceTest` | 各角色權限矩陣 |

---

## 12. 驗收標準

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

### Phase 2 驗收標準

| ID | 標準 | 測試方式 |
|----|------|----------|
| AC-AUTH-10 | ADMIN 可存取所有 Admin 端點 | Unit Test |
| AC-AUTH-11 | 非 ADMIN 存取 Admin 端點回傳 403 | Unit Test |
| AC-AUTH-12 | CUSTOMER 無法修改他人 Ticket | Unit Test |
| AC-AUTH-13 | CUSTOMER 可修改自己的 Ticket | Unit Test |
| AC-AUTH-14 | AGENT 可修改所有 Ticket | Unit Test |
| AC-AUTH-15 | ADMIN 可刪除任何 Ticket | Unit Test |
| AC-AUTH-16 | 非 ADMIN 刪除 Ticket 回傳 403 | Unit Test |

---

## 13. 尚未定義事項

| 項目 | 問題 |
|------|------|
| Agent 範圍限制 | Agent 是否需依部門/範圍限制可操作工單？ |
| Token 撤銷策略 | 除了手動 Logout，是否需要其他自動撤銷機制？ |

---

## 14. 相關文件

| 文件 | 位置 |
|------|------|
| 專案概覽 | `doc/overview.md` |
| User 需求文件 | `doc/requirements/user.md` |
| Ticket 需求文件 | `doc/requirements/ticket.md`（待建立） |

---

*文件版本: 1.1*
*建立日期: 2026-08-31*
*最後更新: 2026-08-31*
*變更記錄:*
*- v1.1 (2026-08-31): 安全性強化 + 架構簡化*
*  - 移除 Refresh Token 機制，簡化實作並減少攻擊面*
*  - Access Token 有效期從 24 小時縮短至 2-4 小時*
*  - 修正 Logout 越權漏洞（禁止信任前端傳入的過期時間）*
*  - 優化 JwtAuthenticationFilter 執行順序（先查黑名單再驗簽章）*
*  - 新增 Rate Limiting Lua Script 原子性實作要求*
