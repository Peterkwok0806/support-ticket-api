# Support Ticket System

多角色客服工單管理平台，支援 Customer/Agent/Admin 三種角色，提供工單建立、狀態流轉、SLA 監控、稽核日誌等完整功能。

## 快速啟動

### 一鍵啟動（PowerShell）

`run-local.ps1` 執行以下操作：
1. 讀取 `.env` 環境變數
2. 啟動 PostgreSQL、Redis、MailHog
3. 使用 `local` profile 啟動 Spring Boot

```powershell
.\run-local.ps1
```

### 手動啟動

```bash
# 1. 複製環境變數（必要）
cp .env.example.env .env

# 2. 啟動 Docker 服務
docker compose up -d

# 3. 啟動後端
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

### 環境變數

啟動前需複製並填寫 `.env` 檔案：

```bash
cp .env.example.env .env
```

| 變數 | 說明 | 範例 |
|------|------|------|
| `POSTGRES_DB` | 資料庫名稱 | `support_ticket` |
| `POSTGRES_USER` | 資料庫使用者 | `support_user` |
| `POSTGRES_PASSWORD` | 資料庫密碼 | `change-me` |
| `JWT_SECRET` | JWT 簽章密鑰（需足够長度） | `replace-with-a-long-random-secret` |
| `MAIL_HOST` | SMTP 主機 | `localhost` |
| `MAIL_PORT` | SMTP 連接埠 | `1025` |
| `REDIS_HOST` | Redis 主機 | `localhost` |
| `REDIS_PORT` | Redis 連接埠 | `6380` |

> **注意**：後端透過 `./mvnw spring-boot:run` 在本機執行時，Redis 對外 port 為 `6380`（見 `application-local.yml`）。若後端也執行在 Docker container 中，請改用 `REDIS_HOST=redis` 及 `REDIS_PORT=6379`。

### Redis 連線設定

#### 本機執行 Spring Boot

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6380  # Host port
```

#### Docker Compose 執行 Spring Boot

```yaml
spring:
  data:
    redis:
      host: redis    # Docker service name
      port: 6379     # Container port
```

### Docker Compose 服務

| Service | Port | 說明 |
|---------|------|------|
| **postgres** | 5432 | PostgreSQL 資料庫 |
| **redis** | 6380（對外）/ 6379（容器內） | Redis 快取服務 |
| **mailhog** | 1025（SMTP）/ 8025（Web） | Email 開發工具，攔截所有寄出的郵件 |

### 服務埠口

| 服務 | URL |
|------|-----|
| **API** | http://localhost:8080/api |
| **Swagger UI** | http://localhost:8080/api/swagger-ui.html |
| **OpenAPI JSON** | http://localhost:8080/api/v3/api-docs |
| **PostgreSQL** | localhost:5432 |
| **Redis** | localhost:6380 |
| **MailHog** | http://localhost:8025 |

## 測試帳號

| 角色 | Email | 密碼 |
|------|-------|------|
| **Admin** | admin@example.com | admin123 |
| **Agent** | agent@example.com | agent123 |
| **Customer** | customer@example.com | customer123 |

## 核心功能

- [x] **JWT 認證**：無狀態登入，支援 CUSTOMER / AGENT / ADMIN 三種角色
- [x] **工單管理**：建立、查詢、修改狀態、指派 Agent
- [x] **狀態機**：Open → In Progress → Resolved → Closed，後端強制驗證
- [x] **留言系統**：公開/內部留言（Internal Comment）
- [x] **搜尋過濾**：依狀態、優先級、分類、指派對象、關鍵字查詢
- [x] **分頁支援**：支援分頁、排序、總數統計
- [x] **SLA 監控**：自動計算截止時間，支援 Warning/Breach 通知
- [x] **稽核日誌**：僅提供 append 與查詢，不提供一般 API 修改或刪除。
- [x] **Redis 快取**：Dashboard、Ticket、Category 資料快取
- [x] **Dashboard API**：提供 Open、Overdue、Resolved Today 統計

## 系統架構

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │────▶│   Backend   │────▶│  PostgreSQL │
│  (Browser)  │     │(Spring Boot)│     │             │
│             │     │  Port 8080  │     │             │
└─────────────┘     └──────┬──────┘     └─────────────┘
                           │
                     ┌─────▼─────┐
                     │   Redis    │
                     │  Port 6380 │
                     └───────────┘
```

> 本 repository 目前專注於後端 REST API；前端 client 不包含在此 repository

### ERD（核心實體）

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│     User     │     │    Ticket    │     │    Comment   │
├──────────────┤     ├──────────────┤     ├──────────────┤
│ id (UUID)    │◄───┐│ id (UUID)    │◄────│ id (UUID)    │
│ email        │    ││ ticketNumber │     │ ticketId     │
│ passwordHash │    ││ title        │     │ authorId     │
│ role         │    ││ status       │     │ content      │
│ active       │    ││ priority     │     │ internal     │
└──────────────┘    ││ categoryId   │     └──────────────┘
                    ││ createdBy    │            │
                    ││ assignedTo   │            │
                    ││ slaDeadline  │            ▼
                    │└──────────────┘     ┌──────────────┐
                           │              │  AuditLog    │
                           └─────────────▶├──────────────┤
                                          │ id (UUID)    │
                                          │ ticketId     │
                                          │ actorId      │
                                          │ action       │
                                          │ oldValue     │
                                          │ newValue     │
                                          └──────────────┘
```


## 技術棧

| 層面 | 技術 |
|------|------|
| **後端** | Java 21 + Spring Boot 4.1.1 |
| **認證** | JWT（Stateless）+ BCrypt |
| **ORM** | Spring Data JPA + Hibernate |
| **資料庫** | PostgreSQL 16 + Flyway Migration |
| **快取** | Redis 7（Spring Cache） |
| **API 文件** | OpenAPI 3.0 / Swagger UI（springdoc-openapi） |
| **建構** | Maven |
| **測試** | JUnit 5 + Mockito |
| **容器化** | Docker Compose |

## 技術亮點

### 稽核日誌（Audit Log）
- 所有操作（建立、狀態變更、指派、留言）自動記錄
- 使用 `@Transactional` 確保 Audit Log 與業務操作原子性
- 寫入失敗時業務操作也回滾，保證資料一致性

### SLA 監控
- 依 Priority（LOW / MEDIUM / HIGH / URGENT）自動計算截止時間
- `@Scheduled` Job 每 15 分鐘檢查逾期工單
- 支援 SLA Warning（逾期前 2 小時）與 SLA Breach 通知
- 使用 DB Unique Index 防止重複通知

### 權限控制
- 三層權限：CUSTOMER / AGENT / ADMIN
- Object-Level Authorization：讀取時驗證使用者與 Ticket 的關聯
- 狀態機 + 權限雙重驗證，防止非法操作

### 效能優化
- Redis 快取：Dashboard（1min）、Ticket（5min）、Category（30min）
- `@Cacheable` + `@CacheEvict` 自動管理快取生命週期
- JPA Specification 支援動態多條件查詢

## API 文件

啟動專案後存取：**http://localhost:8080/api/swagger-ui.html**

### API 規則

| 狀態碼 | 說明 |
|--------|------|
| `201 Created` | 成功建立資源 |
| `200 OK` | 成功查詢、更新 |
| `400 Bad Request` | 請求格式錯誤或驗證失敗 |
| `401 Unauthorized` | 未登入或 token 失效 |
| `403 Forbidden` | 無權限執行此操作 |
| `404 Not Found` | 資源不存在 |
| `409 Conflict` | 狀態機不允許的轉換或併發衝突 |

## API 使用範例

### Demo Flow

1. 使用 Customer 帳號登入。
2. 建立一張 Ticket。
3. 使用 Admin 帳號將 Ticket 指派給 Agent。
4. 使用 Agent 帳號將狀態改為 `IN_PROGRESS`。
5. 新增一則 public comment 與一則 internal note。
6. 使用 Customer 帳號確認看不到 internal note。
7. 查詢 Audit Log，確認建立、指派、狀態與留言操作均有記錄。

### 1. 登入

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "admin@example.com", "password": "admin123"}'
```

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

### 2. 建立工單

```bash
curl -X POST http://localhost:8080/api/v1/tickets \
  -H "Authorization: Bearer <ACCESS_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "無法登入系統",
    "description": "輸入正確密碼後仍顯示認證失敗",
    "categoryId": "<category-uuid>",
    "priority": "HIGH"
  }'
```

### 3. 查詢 Ticket

```bash
# 查詢所有工單
curl http://localhost:8080/api/v1/tickets \
  -H "Authorization: Bearer <ACCESS_TOKEN>"


# 查詢單筆（<TICKET_ID> 為建立工單後回傳的 id）
curl http://localhost:8080/api/v1/tickets/<TICKET_ID> \
  -H "Authorization: Bearer <ACCESS_TOKEN>"

```

### 4. 查詢 Audit Log

```bash
curl http://localhost:8080/api/v1/tickets/<TICKET_ID>/audit-logs \
  -H "Authorization: Bearer <ACCESS_TOKEN>"

```

## 測試

```bash
# 執行所有測試
./mvnw test

# 執行特定測試類
./mvnw test -Dtest=TicketServiceTest
```

### 測試類型

| 類型 | 位置 | 覆蓋目標 |
|------|------|----------|
| **Unit Test** | `src/test/java/**/*Test.java` | 商業邏輯（狀態機、權限、SLA 計算、Audit Log） |
| **Application Test** | `SupportTicketApiApplicationTests.java` | Spring Context 啟動驗證 |

## 已知限制

- 本 repository 目前只包含後端 REST API，不包含 React 前端
- Email 目前使用 MailHog 進行 local development
- Dashboard 目前提供 API，不包含 UI
- 無刷新令牌：JWT 過期後需重新登入，無 refresh token 機制
