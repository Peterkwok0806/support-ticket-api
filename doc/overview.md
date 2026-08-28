# Customer Support Ticket System - Overview

## 專案定位

**多角色客服工單管理平台** — 一個為小型企業設計的全端解決方案，支援多人協作處理客服工單。

### 核心流程

```
Customer 建立問題 → 系統依規則標記 Priority/SLA → Agent 接手與回覆 → Admin 管理與報表 → 所有操作可稽核
```

---

## 技術架構

### 後端架構

| 項目 | 技術 |
|------|------|
| **Framework** | Java 21 + Spring Boot 4.1.1 |
| **架構模式** | Modular Monolith（package-by-feature） |
| **認證** | Stateless JWT Authentication |
| **密碼安全** | BCrypt 雜湊 |
| **ORM** | Spring Data JPA + Hibernate |
| **資料庫** | PostgreSQL |
| **資料庫遷移** | Flyway 版本化管理 |
| **快取** | Redis |
| **API 文件** | OpenAPI / Swagger UI（springdoc-openapi 3.1.0） |
| **健康檢查** | Spring Actuator |
| **建構工具** | Maven |

### 專案結構

```
backend/
├── common/              # 通用：domain、config、exception、security、web、response
├── auth/                # 認證模組
├── users/               # 使用者管理
├── tickets/             # 工單核心
├── comments/            # 留言功能
├── attachments/         # 附件管理
├── sla/                 # SLA 計算
├── notifications/       # 通知系統
├── audit/               # 稽核日誌
└── reporting/           # 報表統計
```

### 前端
- **Framework**: React + TypeScript
- **建構工具**: Vite
- **功能目錄**: features/auth、features/tickets、features/dashboard、features/admin

---

## 資料模型

### Entity 總覽

| Entity | 主要欄位 | 用途 |
|--------|----------|------|
| **User** | id, email, passwordHash, role, active | 使用者與角色 |
| **Ticket** | id, ticketNumber, title, description, status, priority, category, customerId, assigneeId, createdAt, updatedAt, slaDueAt, resolvedAt, version | 工單核心 |
| **Comment** | id, ticketId, authorId, content, internal, createdAt | 溝通留言 |
| **Attachment** | id, ticketId, commentId, originalFilename, storageKey, contentType, size | 檔案附件 |
| **AuditLog** | id, actorId, ticketId, action, beforeValue, afterValue, occurredAt | 操作稽核 |
| **Category** | id, name, defaultPriority, slaHours, active | 分類管理 |
| **Notification** | id, recipientId, type, payload, status, sentAt | 通知系統 |

### Ticket 版本控制
- 使用 **Optimistic Locking**（`version` 欄位 + JPA `@Version`）
- 防止兩位 Agent 同時修改同一工單時互相覆蓋
- 衝突時拋出 `ConflictException`

---

## 角色權限

| 角色 | 權限範圍 |
|------|----------|
| **CUSTOMER** | 建立、查看自己的 tickets、補充公開留言 |
| **AGENT** | 處理被指派的 tickets、修改狀態、公開/內部留言 |
| **ADMIN** | 管理所有 tickets、使用者、分類與 SLA 設定 |

### Object-Level Authorization
每次讀取或修改 ticket 都由後端檢查「此登入者是否有權存取這張資料」。（OWASP API Security Risk）

---

## Ticket 狀態流轉

狀態由後端強制控制，而非依賴前端按鈕：

```
Open ──────────────────→ In Progress ──────────→ Resolved ──────────→ Closed
  ↑                            │                        │
  └────────────────────────────┘                        │
       In Progress ←────────────────────────────────────┘
  │
  └─────────────────────────────────────→ Closed
```

### 狀態說明
- **Open**: 新建立，等待處理
- **In Progress**: Agent 正在處理中
- **Resolved**: 問題已解決，等待客戶確認
- **Closed**: 已結案

---

## 功能清單

### 必做功能（MVP）

| 模組 | 功能 |
|------|------|
| **認證** | JWT 登入、角色權限驗證、密碼 BCrypt 雜湊 |
| **Ticket** | CRUD、狀態流轉、指派 Agent、Priority/SLA 標記 |
| **留言** | 公開/內部留言（internal comment） |
| **搜尋** | status、priority、category、assigneeId、日期範圍、關鍵字（Spring Data Specification） |
| **分頁** | content、page、size、totalElements、totalPages、sort |
| **資料庫** | PostgreSQL + Flyway migration（V1__create_initial_schema.sql） |
| **驗證** | Bean Validation、DTO 請求驗證（@Valid、@NotBlank 等） |
| **例外處理** | 統一例外處理（ApiExceptionHandler）、自訂 BusinessRuleException |
| **容器化** | Docker Compose 一鍵啟動 |
| **API 文件** | OpenAPI / Swagger UI |
| **測試** | JUnit + Mockito + Spring Test |

### 加分功能

| 功能 | 說明 |
|------|------|
| **SLA 管理** | 不同 priority 對應不同時限，計算 slaDueAt，逾期標示，定時 Job 掃描 |
| **Audit Log** | 所有重要操作可稽核（建立、修改狀態、指派等） |
| **附件上傳** | 檔案類型/大小限制，metadata 儲存，Object Storage 預留 |
| **Redis Cache** | Ticket detail、category 列表快取，eviction 策略說明 |
| **Email/通知** | NotificationService abstraction，MailHog 模擬 SMTP |

### 亮點功能

| 功能 | 說明 |
|------|------|
| **Dashboard** | Open、Overdue、Resolved today、按 priority/category 統計 |
| **CI Pipeline** | GitHub Actions 自動化測試（Backend: Maven + Testcontainers PostgreSQL，Frontend: npm ci + lint + test） |
| **測試資料** | Demo 帳號（Customer、Agent、Admin） |
| **健康檢查** | /actuator/health、/actuator/info |

---

## API 設計

### 分頁響應格式（統一格式）
```json
{
  "content": [...],
  "page": 0,
  "size": 20,
  "totalElements": 150,
  "totalPages": 8,
  "sort": "createdAt,desc"
}
```

### 搜尋條件（Spring Data Specification）
支援多條件組合查詢：
- `status` — Open, In Progress, Resolved, Closed
- `priority` — Low, Medium, High, Critical
- `category` — 分類 ID
- `assigneeId` — 指派 Agent
- `customerId` — 客戶
- `createdAt` — 日期範圍
- `keyword` — 標題/描述關鍵字

### DTO 設計
- 不直接用 Entity 作 request/response
- 建立 CreateTicketRequest、UpdateTicketRequest、TicketResponse 等
- request 層加上 Bean Validation

---

## 測試策略

### Unit Test
- SLA 計算邏輯
- 合法/非法狀態流轉驗證
- 權限判斷邏輯
- Priority 規則

### Integration Test
- 使用 Testcontainers 測試真實 PostgreSQL
- Filter、Search、Pagination 查詢
- 複合條件查詢

### API Integration Test（@WithMockUser）
- Customer 不能讀取他人的 ticket
- Agent 不可執行 Admin 操作
- 未登入請求被拒絕（401/403）

### Transaction Test
- 指派失敗時 audit log 和 notification 不應留下半套資料
- 使用 @Transactional 確保原子性

### E2E Test（可選）
- Playwright 或 Cypress 完整流程：登入 → 建票 → 指派 → 回覆 → resolved

---

## Docker Compose 服務

```yaml
services:
  - frontend      # React + Vite (Port 3000)
  - backend       # Spring Boot API (Port 8080)
  - postgres      # PostgreSQL (Port 5432)
  - redis         # Redis Cache (Port 6379)
  - mailhog       # SMTP 模擬 (Port 1025/8025)
```

---

## 快速啟動

```bash
# 複製環境變數
cp .env.example.env .env

# 一鍵啟動所有服務
docker compose up --build

# 或本地啟動（需要已安裝 Java 21、Maven、PostgreSQL、Redis）
./mvnw spring-boot:run
```

### 服務存取

| 服務 | URL |
|------|-----|
| **API** | http://localhost:8080 |
| **Swagger UI** | http://localhost:8080/swagger-ui.html |
| **OpenAPI JSON** | http://localhost:8080/v3/api-docs |
| **Frontend** | http://localhost:3000 |
| **MailHog** | http://localhost:8025 |
| **Actuator Health** | http://localhost:8080/actuator/health |

### Demo 帳號

| 角色 | Email | 密碼 |
|------|-------|------|
| Admin | admin@example.com | admin123 |
| Agent | agent@example.com | agent123 |
| Customer | customer@example.com | customer123 |

---

## 未來改善方向

- **Refresh Token Rotation** — JWT 無效化機制
- **Object Storage（S3）** — 附件上傳到雲端儲存
- **Rate Limiting** — API 頻率限制
- **監控儀表板** — Prometheus + Grafana
- **RBAC Policy Engine** — 更精細的權限控制

---

## 建議完成順序

```
1. 認證與角色 ───→ 2. Ticket 流程 ───→ 3. Comment/Audit ───→ 4. Search/Pagination
                                              │
5. Docker/CI ────────────────────────────────→│
                                              ↓
                    8. Redis/Email/Attachment ← 6. SLA ← 7. Dashboard
```

> 完成一個乾淨、可測試、可啟動的版本，會比做很多半完成的企業功能更有說服力。

---

## 參考資源

- [Spring Boot Documentation](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [Spring Security Reference](https://docs.spring.io/spring-security/reference/)
- [Spring Data JPA](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/)
- [Flyway Documentation](https://documentation.red-gate.com/flyway/)
- [springdoc-openapi](https://springdoc.org/)
- [OWASP API Security Top 10](https://owasp.org/API-Security/)
