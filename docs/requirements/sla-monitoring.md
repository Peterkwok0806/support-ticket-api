# SLA 排程與通知機制需求文件

## 1. 功能概述

### 1.1 功能目標

自動監控 Ticket 的 SLA 到期狀態，在即將逾期或已逾期時主動通知相關人員，避免 Ticket 被遺忘或處理延遲。

### 1.2 核心價值

| 角色 | 價值 |
|------|------|
| **Customer** | 知道系統有在追蹤處理時效，增加對服務品質的信任 |
| **Agent** | 收到逾期警告，優先處理高風險 Ticket；避免遺忘或延遲處理 |
| **Admin** | 監控團隊的 SLA 達成率；識別哪些 Agent 負責的 Ticket 常逾期 |

---

## 2. 使用者故事

### 2.1 SLA Warning（即將逾期通知）

```
身為: Agent
當有: Ticket 即將在 1-2 小時內逾期
我希望: 收到系統通知提醒
以便: 提前處理，避免真的逾期
```

**驗收標準**：
- [ ] Ticket 的 `slaDeadline` 在未來 1-2 小時內，且狀態為 OPEN/IN_PROGRESS/WAITING_ON_CUSTOMER
- [ ] Agent 會收到 In-app 通知
- [ ] 同一張 Ticket 的同一種 SLA 通知只發送一次（不重複通知）

### 2.2 SLA Breach（已逾期通知）

```
身為: Agent 或 Admin
當有: Ticket 已超過 SLA deadline 未解決
我希望: 收到系統通知
以便: 立即處理逾期工單
```

**驗收標準**：
- [ ] Ticket 的 `slaDeadline` 已過期，且狀態為 OPEN/IN_PROGRESS/WAITING_ON_CUSTOMER
- [ ] Agent 和 Admin 都會收到通知
- [ ] 同一張 Ticket 的 SLA_BREACH 通知只發送一次

### 2.3 通知查詢

```
身為: 任何登入使用者
當我想: 查看我的通知
我希望: 能夠瀏覽、分頁、標記已讀
以便: 追蹤需要關注的事項
```

**驗收標準**：
- [ ] 可查詢自己的通知列表（分頁）
- [ ] 可篩選只看未讀通知
- [ ] 可標記單筆或全部通知為已讀
- [ ] 可查看未讀通知數量
- [ ] 無法查看他人的通知

### 2.4 Email 通知（非同步）

```
身為: Agent 或 Admin
當我收到: SLA 逾期相關通知
我希望: 同步收到 Email 通知
以便: 不登入系統也能收到提醒
```

**驗收標準**：
- [ ] SLA 通知發送時，同步發送 Email
- [ ] Email 發送失敗不影響 Job 主流程（僅記錄 log）
- [ ] Email 發送為非同步，不阻塞 SLA 監控 Job

---

## 3. 業務規則

### 3.1 通知觸發條件

| 通知類型 | 觸發條件 | 通知對象 |
|----------|----------|----------|
| SLA_WARNING | `now < slaDeadline <= now + 2h` 且狀態為 OPEN/IN_PROGRESS/WAITING_ON_CUSTOMER | Agent（負責人） |
| SLA_BREACH | `slaDeadline < now` 且狀態為 OPEN/IN_PROGRESS/WAITING_ON_CUSTOMER | Agent + Admin |

### 3.2 防重複通知規則

```
業務語意：「同一張 Ticket 的同一種 SLA 通知，只能發送一次」

範例：
- Ticket #123 的 SLA_WARNING 發送後，無論誰都不會再收到這張 Ticket 的 SLA_WARNING
- Ticket #123 的 SLA_BREACH 發送後，同樣封鎖
- Ticket #456（不同 Ticket）不受影響，可正常發送
```

### 3.3 不通知的情境

以下情境不發送 SLA 通知：
- Ticket 狀態為 RESOLVED 或 CLOSED
- 同一張 Ticket 的該通知類型已發送過

---

## 4. 功能範圍

### 4.1 In Scope（本次實作）

| 功能 | 說明 |
|------|------|
| SLA Breach 檢查 Job | 每 15 分鐘執行，檢查已逾期的 Ticket |
| SLA Warning 檢查 Job | 每 30 分鐘執行，檢查即將逾期的 Ticket |
| In-app 通知儲存 | 將通知存入資料庫，供使用者查詢 |
| 通知查詢 API | 分頁查詢、篩選未讀、標記已讀 |
| Email 通知 | 非同步發送 Email（使用現有 MailHog） |

### 4.2 Out of Scope（未來擴展）

| 功能 | 說明 |
|------|------|
| SLA 升級機制 | 逾期後自動升級給 Manager |
| Slack/Teams 整合 | 其他通知管道 |
| SLA 達成率報表 | Admin Dashboard |
| 使用者通知偏好設定 | 關閉特定類型通知 |
| Admin 手動重發通知 | 管理功能 |
| TICKET_ASSIGNED 等其他通知 | 本次僅實作 SLA 相關通知 |

---

## 5. 通知類型枚舉

```java
public enum NotificationType {
    SLA_WARNING,       // 即將逾期（1-2 小時前）
    SLA_BREACH,        // 已逾期
    TICKET_ASSIGNED,   // 被指派新 Ticket（預留）
    TICKET_RESOLVED,   // Ticket 被標記為已解決（預留）
    TICKET_UPDATED     // Ticket 被更新（預留）
}
```

---

## 6. API 需求

### 6.1 端點需求

| 方法 | 路徑 | 說明 |
|------|------|------|
| GET | `/api/v1/notifications` | 查詢通知列表（分頁） |
| GET | `/api/v1/notifications/{id}` | 查詢單筆通知 |
| GET | `/api/v1/notifications/unread-count` | 取得未讀數量 |
| PATCH | `/api/v1/notifications/{id}/read` | 標記為已讀 |
| PATCH | `/api/v1/notifications/read-all` | 標記所有為已讀 |

### 6.2 查詢參數

| 參數 | 型別 | 預設值 | 說明 |
|------|------|--------|------|
| page | int | 0 | 頁碼 |
| size | int | 20 | 每頁筆數 |
| unreadOnly | boolean | false | 只顯示未讀 |

### 6.3 權限需求

| 端點 | 權限 |
|------|------|
| GET /notifications | 僅能查詢自己的通知 |
| GET /{id} | 僅 notification 持有人可查詢 |
| PATCH /{id}/read | 僅 notification 持有人可標記 |
| PATCH /read-all | 僅能標記自己的通知 |

---

## 7. 非功能性需求

### 7.1 效能需求

| 項目 | 需求 |
|------|------|
| Job 執行頻率 | Breach: 每 15 分鐘，Warning: 每 30 分鐘 |
| Job 執行時間 | 單次執行不超過 5 分鐘 |
| Email 發送 | 非同步執行，不阻塞 Job |

### 7.2 可靠性需求

| 項目 | 需求 |
|------|------|
| Job 失敗處理 | 記錄 log，不影響下次執行 |
| Email 發送失敗 | 僅記錄 log，不影響 Job 主流程 |
| 資料一致性 | 使用 Unique Index 防止重複通知 |

---

## 8. 驗收標準摘要

### 8.1 SLA Warning 功能

- [ ] 每 30 分鐘檢查即將逾期的 Ticket
- [ ] 通知發送至資料庫
- [ ] 同一張 Ticket 同一通知只發送一次
- [ ] 不通知已關閉的 Ticket

### 8.2 SLA Breach 功能

- [ ] 每 15 分鐘檢查已逾期的 Ticket
- [ ] 通知發送至資料庫
- [ ] 同一張 Ticket 同一通知只發送一次
- [ ] 不通知已關閉的 Ticket

### 8.3 通知查詢 API

- [ ] 分頁查詢通知列表
- [ ] 可篩選未讀通知
- [ ] 可標記單筆/全部為已讀
- [ ] 可查詢未讀數量
- [ ] 無法查看他人通知

### 8.4 Email 通知

- [ ] SLA 通知同步發送 Email
- [ ] Email 發送為非同步
- [ ] 發送失敗不影響 Job

---

## 9. 實作優先順序

```
P1 (必做):
  1. Notification Entity & Repository
  2. NotificationService 基本功能
  3. SLA 排程 Job
  4. 通知 API

P2 (重要):
  5. Email 通知
  6. 單元測試

P3 (可延後):
  7. 整合測試
```
