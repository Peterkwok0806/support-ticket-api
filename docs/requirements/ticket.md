# Ticket 核心功能需求

## 1. 概述

Ticket 是系統的核心 Domain Entity，用於追蹤客戶支援案件。

## 2. 操作權限

### 2.1 角色權限矩陣

| 操作 | CUSTOMER | AGENT | ADMIN |
|------|:--------:|:-----:|:-----:|
| 建立 Ticket | ✅ | ❌ | ✅ |
| 讀取自己的 Ticket | ✅ | ✅ | ✅ |
| 讀取被指派的 Ticket | ❌ | ✅ | ✅ |
| 讀取所有 Ticket | ❌ | ❌ | ✅ |
| 補充 description | ✅（自己的）| ✅（被指派的）| ✅ |
| 更新 Ticket（title、priority、categoryId） | ❌ | ❌ | ✅ |
| 變更狀態 | ❌ | ✅（被指派的）| ✅ |
| 指派 Ticket | ❌ | ❌ | ✅ |
| 刪除 Ticket | ❌ | ❌ | ✅ |

### 2.2 權限說明

- **CUSTOMER**：客戶，只能管理自己建立的 Ticket
- **AGENT**：客服人員，只能處理被指派給自己的 Ticket
- **ADMIN**：管理者，可操作所有 Ticket

---

## 3. Domain Model

### 3.1 Ticket Entity

| 欄位 | 型別 | 說明 | 約束 |
|------|------|------|------|
| id | UUID | 主鍵 | 自動生成 |
| title | String | 標題 | 必填，最大 200 字 |
| description | String | 描述 | TEXT，可為空 |
| status | TicketStatus | 狀態 | 必填，預設 OPEN |
| priority | TicketPriority | 優先級 | 必填，預設 MEDIUM |
| categoryId | UUID | 分類 ID | FK，非空 |
| createdBy | UUID | 建立者 ID | FK，非空 |
| assignedTo | UUID | 指派對象 ID | FK，可為空 |
| resolvedAt | Instant | 解決時間 | 可為空 |
| closedAt | Instant | 關閉時間 | 可為空 |
| firstResponseAt | Instant | 首次回應時間 | 可為空 |
| slaDeadline | Instant | SLA 截止時間 | 可為空 |
| version | Long | 樂觀鎖版本 | 自動維護 |

### 3.2 現有枚舉複用

- `TicketStatus`：OPEN, IN_PROGRESS, RESOLVED, CLOSED
- `TicketPriority`：LOW, MEDIUM, HIGH, URGENT
- `AuditAction`：用於記錄操作審計日誌

## 4. 狀態機

### 4.1 狀態定義

```
┌─────┐    ┌─────────────┐    ┌─────────┐    ┌────────┐
│OPEN │───▶│ IN_PROGRESS │───▶│RESOLVED │───▶│ CLOSED │
└─────┘    └─────────────┘    └─────────┘    └────────┘
```

- **OPEN**：新建立，等待處理
- **IN_PROGRESS**：處理中
- **RESOLVED**：已解決，等待確認關閉
- **CLOSED**：已關閉，**不可再變更**

### 4.2 狀態轉換規則

| 當前狀態 | 允許轉換至 | 說明 |
|----------|-----------|------|
| OPEN | IN_PROGRESS, CLOSED | 開始處理或直接關閉 |
| IN_PROGRESS | OPEN, RESOLVED | 退回或完成處理 |
| RESOLVED | CLOSED, OPEN | 關閉或退回重新處理 |
| CLOSED | — | **終態，不可變更** |

### 4.3 狀態轉換副作用

| 轉換 | 副作用 |
|------|--------|
| → RESOLVED | 設定 `resolvedAt` |
| → CLOSED | 設定 `closedAt` |
| → IN_PROGRESS | 若 `firstResponseAt` 為空，設定當下時間 |
| 優先級變更 | 重新計算 `slaDeadline` |

## 5. 功能需求

### 5.1 建立 Ticket（Create）

- 角色： CUSTOMER、ADMIN
- 必填欄位：title、categoryId
- 可選欄位：description、priority（預設 MEDIUM）
- 建立時自動設定：
  - `status = OPEN`
  - `createdBy = currentUser`
  - `slaDeadline = SlaCalculator.calculateSlaDueAt(category, priority, createdAt)`

### 5.2 查詢 Ticket（Read）

- 依 ID 查詢詳情
- 分頁列表查詢，支援過濾條件：
  - status（單一或多選）
  - priority
  - categoryId
  - assignedTo
  - createdBy
  - keyword（title 模糊搜尋）
- 權限過濾：
  - ADMIN：可查詢所有
  - AGENT：自動過濾為 `assignedTo = currentUser`
  - CUSTOMER：自動過濾為 `createdBy = currentUser`

### 5.3 更新 Ticket（Update）

- 角色：ADMIN
- 可更新欄位：title、description、priority、categoryId
- 優先級變更時重新計算 SLA

### 5.4 變更狀態（Change Status）

- 角色：AGENT、ADMIN
- AGENT 只能變更加給自己的 Ticket
- 呼叫狀態機驗證轉換是否有效
- CLOSED 為終態，嘗試轉換時拋出例外

### 5.5 指派 Ticket（Assign）

- 角色：ADMIN
- 設定 assignedTo（可為 null 表示取消指派）
- 支援驗證指派對象是否存在

## 6. API 端點

### 6.1 TicketController（`/api/v1/tickets`）

| Method | Path | 角色 | 說明 |
|--------|------|------|------|
| POST | / | CUSTOMER, ADMIN | 建立 Ticket |
| GET | / | ALL | 分頁列表查詢 |
| GET | /{id} | ALL | 依 ID 查詢 |
| PATCH | /{id} | ADMIN | 更新基本資訊 |
| PATCH | /{id}/status | AGENT, ADMIN | 變更狀態 |
| PATCH | /{id}/assign | ADMIN | 指派 Ticket |

### 6.2 TicketAdminController（`/api/v1/admin/tickets`）

| Method | Path | 角色 | 說明 |
|--------|------|------|------|
| DELETE | /{id} | ADMIN | 刪除 Ticket |

## 7. 異常處理

| 例外類型 | HTTP Status | 觸發條件 |
|----------|-------------|----------|
| ResourceNotFoundException | 404 | Ticket 不存在 |
| BusinessRuleException | 400 | 商業規則違反（如必填欄位缺失、驗證失敗） |
| ForbiddenOperationException | 409 | 無權限操作或狀態機不允許的轉換 |
| ConflictException | 409 | 資料衝突（如重複建立） |

## 8. 非功能性需求

- 使用 JPA Specification 支援動態查詢
- 使用 VersionedEntity 實現樂觀鎖
- 使用 Flyway 管理資料庫 Migration
- 所有修改操作需包在 Transaction 中
- 唯讀查詢使用 `@Transactional(readOnly = true)`

## 9. Out of Scope（本次不實作）

- Comment（評論）功能
- Attachment（附件）功能
- Notification（通知）功能
- Ticket 軟刪除（is_deleted 欄位）
- 多步驟審批流程

## 10. 預計產出檔案

```
src/main/java/com/pk/support_ticket_api/
└── tickets/
    ├── domain/
    │   ├── Ticket.java
    │   └── TicketSpecification.java
    ├── dto/
    │   ├── CreateTicketRequest.java
    │   ├── UpdateTicketRequest.java
    │   ├── TicketStatusUpdateRequest.java
    │   ├── TicketResponse.java
    │   └── TicketSummaryResponse.java
    ├── repository/
    │   └── TicketRepository.java
    ├── service/
    │   ├── TicketService.java
    │   ├── TicketServiceImpl.java
    │   └── TicketStateMachine.java
    └── web/
        ├── TicketController.java
        └── TicketAdminController.java

src/main/resources/db/migration/
└── V{version}__create_tickets_table.sql

src/test/java/com/pk/support_ticket_api/tickets/
├── service/
│   ├── TicketServiceTest.java
│   └── TicketStateMachineTest.java
└── web/
    └── TicketControllerTest.java
```
