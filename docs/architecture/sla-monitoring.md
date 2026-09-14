# SLA 排程與通知機制架構設計

## 1. 模組結構

```
notifications/
├── domain/
│   ├── Notification.java          # Entity
│   └── NotificationType.java       # Enum
├── dto/
│   ├── NotificationResponse.java
│   └── NotificationSummaryResponse.java
├── repository/
│   └── NotificationRepository.java
├── service/
│   ├── NotificationService.java    # Interface
│   └── NotificationServiceImpl.java
└── web/
    └── NotificationController.java # 查詢自己的通知

sla/
├── job/
│   └── SlaMonitoringJob.java       # Spring @Scheduled Job
├── service/
│   ├── SlaMonitoringService.java   # SLA 監控核心
│   └── EmailNotificationService.java # Email 發送（非同步）
└── domain/
    └── TicketSlaSpecification.java # SLA 查詢條件
```

---

## 2. 依賴關係圖

```
┌──────────────────────────────────────────────────────────────┐
│                         job/                                  │
│                     SlaMonitoringJob                          │
│  @Scheduled(cron = "0 */15 * * * *")  // 每 15 分鐘         │
└──────────────────────┬───────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────┐
│                      service/                                │
│                    SlaMonitoringService                       │
│  1. 查詢即將逾期 / 已逾期的 Tickets                         │
│  2. 呼叫 NotificationService 發送通知                       │
└──────────────────────┬───────────────────────────────────────┘
                       │
        ┌──────────────┴──────────────┐
        ▼                             ▼
┌───────────────────┐      ┌───────────────────┐
│ TicketRepository  │      │ NotificationService │
│ (SLA 查詢)        │      │ (發送通知)           │
└───────────────────┘      └───────────────────┘
```

---

## 3. 資料流程

```
[Scheduler 觸發]
      │
      ▼
[SlaMonitoringJob.checkSlaBreach()]
      │
      ▼
[查詢 SLA Breach Tickets]
  WHERE status NOT IN (RESOLVED, CLOSED)
  AND sla_deadline < NOW()
      │
      ▼
[嘗試發送 Notification (SLA_BREACH)]
      │
      ├──► [儲存至 Notification 表]
      │        └──► [Unique Index 防止重複]
      │
      └──► [EmailNotificationService.send()]  // 非同步

[Scheduler 觸發]
      │
      ▼
[SlaMonitoringJob.checkSlaWarning()]
      │
      ▼
[查詢 SLA Warning Tickets]
  WHERE status NOT IN (RESOLVED, CLOSED)
  AND sla_deadline BETWEEN NOW() AND NOW() + 2h
      │
      ▼
[嘗試發送 Notification (SLA_WARNING)]
      │
      ├──► [儲存至 Notification 表]
      │
      └──► [EmailNotificationService.send()]  // 非同步
```

---

## 4. 資料模型設計

### 4.1 Notification Entity

```sql
CREATE TABLE notifications (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ticket_id       UUID         NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    type            VARCHAR(30)  NOT NULL
                    CHECK (type IN (
                        'SLA_WARNING',
                        'SLA_BREACH',
                        'TICKET_ASSIGNED',
                        'TICKET_RESOLVED',
                        'TICKET_UPDATED'
                    )),
    title           VARCHAR(200) NOT NULL,
    message         TEXT         NOT NULL,
    is_read         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 防止 SLA 通知重複發送（業務需求）
-- 業務語意：「同一張 Ticket 的同一種 SLA 通知，只能發送一次」
CREATE UNIQUE INDEX idx_sla_unique_notify
    ON notifications(ticket_id, type)
    WHERE type IN ('SLA_WARNING', 'SLA_BREACH');

-- 一般查詢索引
CREATE INDEX idx_notifications_recipient
    ON notifications(recipient_id, is_read, created_at DESC);

CREATE INDEX idx_notifications_ticket
    ON notifications(ticket_id);
```

### 4.2 NotificationType Enum

```java
public enum NotificationType {
    SLA_WARNING,       // 即將逾期（1-2 小時前）
    SLA_BREACH,        // 已逾期
    TICKET_ASSIGNED,   // 被指派新 Ticket
    TICKET_RESOLVED,   // Ticket 被標記為已解決
    TICKET_UPDATED     // Ticket 被更新
}
```

### 4.3 防重複通知機制

**實作時的處理邏輯**：

```java
// SlaMonitoringService 中的防重複邏輯
public void processSlaBreaches() {
    List<Ticket> breachedTickets = ticketRepository.findSlaBreached();

    for (Ticket ticket : breachedTickets) {
        try {
            notificationService.sendSlaNotification(ticket, NotificationType.SLA_BREACH);
        } catch (DataIntegrityViolationException e) {
            // Unique Index 衝突，代表已發送過，跳過
            log.debug("SLA breach notification already sent for ticket: {}", ticket.getId());
        }
    }
}
```

---

## 5. 排程設計

### 5.1 排程頻率

| 檢查類型 | 頻率 | 說明 |
|----------|------|------|
| SLA Breach | 每 15 分鐘 | 確保逾期後盡快通知 |
| SLA Warning | 每 30 分鐘 | 提前 1-2 小時警告 |

### 5.2 Spring @Scheduled 實作

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SlaMonitoringJob {

    private final SlaMonitoringService slaMonitoringService;

    // SLA Breach 檢查：每 15 分鐘
    @Scheduled(cron = "0 */15 * * * *")
    public void checkSlaBreach() {
        log.info("Starting SLA breach check...");
        try {
            slaMonitoringService.processSlaBreaches();
        } catch (Exception e) {
            log.error("Error during SLA breach check", e);
        }
    }

    // SLA Warning 檢查：每 30 分鐘
    @Scheduled(cron = "0 */30 * * * *")
    public void checkSlaWarning() {
        log.info("Starting SLA warning check...");
        try {
            slaMonitoringService.processSlaWarnings();
        } catch (Exception e) {
            log.error("Error during SLA warning check", e);
        }
    }
}
```

### 5.3 查詢 Specification

```java
public class TicketSlaSpecification {

    private static final Duration WARNING_WINDOW = Duration.ofHours(2);

    /**
     * 查詢即將逾期的 Tickets（用於 Warning）
     * 條件：
     * - 狀態為 OPEN, IN_PROGRESS, WAITING_ON_CUSTOMER
     * - SLA 尚未逾期（slaDeadline > now）
     * - 在警告範圍內（slaDeadline <= now + 2h）
     */
    public static Specification<Ticket> isApproachingSlaBreach() {
        Instant now = Instant.now();
        Instant warningThreshold = now.plus(WARNING_WINDOW);

        return (root, query, cb) -> cb.and(
            root.get("status").in(
                TicketStatus.OPEN,
                TicketStatus.IN_PROGRESS,
                TicketStatus.WAITING_ON_CUSTOMER
            ),
            cb.greaterThan(root.get("slaDeadline"), now),
            cb.lessThanOrEqualTo(root.get("slaDeadline"), warningThreshold)
        );
    }

    /**
     * 查詢已逾期的 Tickets（用於 Breach）
     * 條件：
     * - 狀態為 OPEN, IN_PROGRESS, WAITING_ON_CUSTOMER
     * - SLA 已逾期（slaDeadline < now）
     */
    public static Specification<Ticket> isSlaBreached() {
        return (root, query, cb) -> cb.and(
            root.get("status").in(
                TicketStatus.OPEN,
                TicketStatus.IN_PROGRESS,
                TicketStatus.WAITING_ON_CUSTOMER
            ),
            cb.lessThan(root.get("slaDeadline"), Instant.now())
        );
    }
}
```

---

## 6. 通知服務設計

### 6.1 NotificationService 介面

```java
public interface NotificationService {

    /**
     * 發送 SLA 逾期通知
     * @param ticket 逾期的 Ticket
     * @param type 通知類型（SLA_WARNING 或 SLA_BREACH）
     * @throws DataIntegrityViolationException 若已發送過（Unique Index 衝突）
     */
    void sendSlaNotification(Ticket ticket, NotificationType type);

    /**
     * 查詢使用者的通知列表（分頁）
     */
    PageResponse<NotificationSummaryResponse> getNotifications(
            UUID recipientId,
            Pageable pageable
    );

    /**
     * 查詢單筆通知
     */
    NotificationResponse getNotificationById(UUID notificationId, UUID recipientId);

    /**
     * 標記通知為已讀
     */
    void markAsRead(UUID notificationId, UUID recipientId);

    /**
     * 標記所有通知為已讀
     */
    void markAllAsRead(UUID recipientId);

    /**
     * 取得未讀通知數量
     */
    long getUnreadCount(UUID recipientId);
}
```

### 6.2 通知內容範本

```java
public record NotificationContent(
    String title,
    String message
) {
    public static NotificationContent forSlaWarning(Ticket ticket) {
        return new NotificationContent(
            String.format("[SLA 警告] Ticket 即將逾期"),
            String.format("Ticket「%s」預計於 %s 逾期，請儘早處理。",
                ticket.getTitle(),
                formatDeadline(ticket.getSlaDeadline())
            )
        );
    }

    public static NotificationContent forSlaBreach(Ticket ticket) {
        return new NotificationContent(
            String.format("[SLA 逾期] Ticket 已逾期！"),
            String.format("Ticket「%s」已於 %s 逾期，需要立即處理！",
                ticket.getTitle(),
                formatDeadline(ticket.getSlaDeadline())
            )
        );
    }

    private static String formatDeadline(Instant deadline) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(deadline);
    }
}
```

### 6.3 非同步 Email 發送

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailNotificationService {

    private final JavaMailSender mailSender;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    @Async("notificationExecutor")
    public void sendSlaAlertEmail(UUID ticketId, NotificationType type) {
        List<Notification> notifications = notificationRepository
            .findByTicketIdAndType(ticketId, type);

        for (Notification notification : notifications) {
            User recipient = userRepository.findById(notification.getRecipientId())
                .orElse(null);

            if (recipient == null || recipient.getEmail() == null) {
                continue;
            }

            try {
                sendEmail(recipient.getEmail(), notification);
            } catch (Exception e) {
                log.error("Failed to send email to {}: {}",
                    recipient.getEmail(), e.getMessage());
            }
        }
    }

    private void sendEmail(String to, Notification notification) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject(notification.getTitle());
        message.setText(notification.getMessage());
        mailSender.send(message);
    }
}
```

### 6.4 Thread Pool 設定

```java
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class AsyncConfig {

    @Bean("notificationExecutor")
    public Executor notificationExecutor() {
        return ThreadPoolTaskExecutorBuilder.create()
            .corePoolSize(2)
            .maxPoolSize(5)
            .queueCapacity(100)
            .threadNamePrefix("notification-")
            .rejectedExecutionHandler(new Log4j2RejectionHandler())
            .build();
    }

    private static class Log4j2RejectionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("Notification task rejected due to queue overflow");
        }
    }
}
```

---

## 7. API 設計

### 7.1 端點清單

| 方法 | 路徑 | 說明 |
|------|------|------|
| GET | `/api/v1/notifications` | 查詢通知列表（分頁） |
| GET | `/api/v1/notifications/{id}` | 查詢單筆通知 |
| GET | `/api/v1/notifications/unread-count` | 取得未讀數量 |
| PATCH | `/api/v1/notifications/{id}/read` | 標記為已讀 |
| PATCH | `/api/v1/notifications/read-all` | 標記所有為已讀 |

### 7.2 請求與回應格式

**GET /api/v1/notifications**

```
Query Parameters:
- page: int (default 0)
- size: int (default 20)
- unreadOnly: boolean (default false)

Response:
{
  "content": [
    {
      "id": "uuid",
      "ticketId": "uuid",
      "type": "SLA_BREACH",
      "title": "[SLA 逾期] Ticket 已逾期！",
      "message": "Ticket「無法登入」已於 2024-01-15 10:00 逾期，需要立即處理！",
      "isRead": false,
      "createdAt": "2024-01-15T10:05:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "sort": "createdAt,desc"
}
```

**GET /api/v1/notifications/unread-count**

```json
{
  "count": 5
}
```

### 7.3 權限設計

| 端點 | 權限 |
|------|------|
| GET /notifications | 僅能查詢自己的通知（根據 JWT 中的 userId） |
| GET /{id} | 僅 notification 持有人可查詢 |
| PATCH /{id}/read | 僅 notification 持有人可標記 |
| PATCH /read-all | 僅能標記自己的通知 |

---

## 8. 技術決策

| 決策 | 選擇 | 理由 |
|------|------|------|
| 排程框架 | Spring @Scheduled | 輕量，符合現有架構 |
| 非同步處理 | @Async + ThreadPoolTaskExecutor | 避免 Email 發送阻塞 Job |
| 防重複通知 | DB Partial Unique Index | 語意明確，fail-fast，資料庫保證 |
| 查詢方式 | JPA Specification | 與現有架構一致 |
| Email 發送 | Spring Mail + Async | 現有專案已有 MailHog |

---

## 9. 預計產出檔案

### 9.1 新建檔案

| 檔案路徑 | 說明 |
|----------|------|
| `src/main/resources/db/migration/V8__create_notifications_table.sql` | 通知表遷移 |
| `src/main/java/.../notifications/domain/Notification.java` | Entity |
| `src/main/java/.../notifications/domain/NotificationType.java` | Enum |
| `src/main/java/.../notifications/dto/NotificationResponse.java` | 單筆查詢回應 |
| `src/main/java/.../notifications/dto/NotificationSummaryResponse.java` | 列表查詢回應 |
| `src/main/java/.../notifications/repository/NotificationRepository.java` | Repository |
| `src/main/java/.../notifications/service/NotificationService.java` | 介面 |
| `src/main/java/.../notifications/service/NotificationServiceImpl.java` | 實作 |
| `src/main/java/.../notifications/web/NotificationController.java` | REST API |
| `src/main/java/.../sla/job/SlaMonitoringJob.java` | 排程 Job |
| `src/main/java/.../sla/service/SlaMonitoringService.java` | SLA 監控邏輯 |
| `src/main/java/.../sla/service/EmailNotificationService.java` | Email 發送 |
| `src/main/java/.../sla/domain/TicketSlaSpecification.java` | SLA 查詢條件 |
| `src/main/java/.../common/config/AsyncConfig.java` | Async + ThreadPool 設定 |

### 9.2 修改檔案

| 檔案路徑 | 修改內容 |
|----------|----------|
| `pom.xml` | 加入 `spring-boot-starter-mail` 依賴 |
| `src/main/java/.../common/config/SecurityConfig.java` | 開放 `/api/v1/notifications/**` 端點 |

---

## 10. 測試策略

### 10.1 單元測試

| 測試類 | 覆蓋重點 |
|--------|----------|
| `TicketSlaSpecificationTest` | Warning/Breach 查詢條件正確性 |
| `SlaMonitoringServiceTest` | 通知發送、去重邏輯 |
| `NotificationServiceImplTest` | 基本 CRUD、權限驗證 |
| `NotificationControllerTest` | API 端點、@PreAuthorize |

### 10.2 整合測試

| 測試類 | 覆蓋重點 |
|--------|----------|
| `SlaMonitoringJobIntegrationTest` | Job 完整流程、Unique Index 防止重複 |
| `NotificationRepositoryTest` | Unique constraint 驗證 |

### 10.3 測試情境

```java
@Test
void shouldNotSendDuplicateSlaBreachNotification() {
    // Given: Ticket 已逾期，且已發送過 SLA_BREACH 通知
    Ticket ticket = createBreachedTicket();
    notificationService.sendSlaNotification(ticket, NotificationType.SLA_BREACH);

    // When: 再次嘗試發送
    // Then: 拋出 DataIntegrityViolationException
    assertThatThrownBy(() ->
        notificationService.sendSlaNotification(ticket, NotificationType.SLA_BREACH)
    ).isInstanceOf(DataIntegrityViolationException.class);
}
```

---

## 11. 實作順序

```
Phase 1: 基礎設施
  1. Database migration (V8__create_notifications_table.sql)
  2. Notification Entity & NotificationType
  3. NotificationRepository
  4. AsyncConfig (ThreadPool 設定)

Phase 2: 通知核心功能
  5. NotificationService 介面與實作
  6. NotificationController
  7. SecurityConfig 開放端點

Phase 3: SLA 監控功能
  8. TicketSlaSpecification (查詢條件)
  9. SlaMonitoringService
  10. SlaMonitoringJob

Phase 4: Email 通知
  11. pom.xml 加入 spring-boot-starter-mail
  12. EmailNotificationService
  13. .env 設定 SMTP（使用 MailHog）

Phase 5: 測試
  14. NotificationServiceImplTest
  15. TicketSlaSpecificationTest
  16. SlaMonitoringServiceTest
```

---

## 12. 風險與緩解

| 風險 | 緩解措施 |
|------|----------|
| Job 執行時間過長 | 使用 @Async，非同步發送 Email |
| 高負載時通知堆積 | ThreadPool queue capacity 100，超過則記錄 warning |
| 時區問題 | 全系統使用 Instant/UTC，Display 時轉換 |
| Email 發送失敗 | 只記錄 log，不影響 Job 主流程 |
| Unique Index 衝突 | 捕獲 DataIntegrityViolationException，視為正常跳過 |
