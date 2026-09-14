# SLA 排程與通知機制實作步驟

## 文件資訊

| 項目 | 內容 |
|------|------|
| 版本 | v1.0 |
| 日期 | 2026-09-14 |
| 狀態 | 待實作 |
| 依賴 | Phase 1-5 依序執行 |

---

## 實作總覽

```
Phase 1: 基礎設施（Database + Entity + Async Config）
Phase 2: 通知核心功能（Service + Controller）
Phase 3: SLA 監控功能（Specification + Job）
Phase 4: Email 通知
Phase 5: 測試
```

---

## Phase 1：基礎設施

### Step 1.1：建立 Database Migration

**檔案**：`src/main/resources/db/migration/V8__create_notifications_table.sql`

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

-- 防止 SLA 通知重複發送
-- 業務語意：同一張 Ticket 的同一種 SLA 通知，只能發送一次
CREATE UNIQUE INDEX idx_sla_unique_notify
    ON notifications(ticket_id, type)
    WHERE type IN ('SLA_WARNING', 'SLA_BREACH');

-- 一般查詢索引
CREATE INDEX idx_notifications_recipient
    ON notifications(recipient_id, is_read, created_at DESC);

CREATE INDEX idx_notifications_ticket
    ON notifications(ticket_id);
```

**驗證**：執行 `mvn flyway:migrate` 確認遷移成功

---

### Step 1.2：建立 NotificationType Enum

**檔案**：`src/main/java/com/pk/support_ticket_api/notifications/domain/NotificationType.java`

```java
package com.pk.support_ticket_api.notifications.domain;

public enum NotificationType {
    SLA_WARNING,       // 即將逾期（1-2 小時前）
    SLA_BREACH,        // 已逾期
    TICKET_ASSIGNED,   // 被指派新 Ticket
    TICKET_RESOLVED,   // Ticket 被標記為已解決
    TICKET_UPDATED     // Ticket 被更新
}
```

**注意**：現有 `NotificationStatus` enum（Pending/Sent/Failed）位於 `common/domain/enums/`，本次不使用

---

### Step 1.3：建立 Notification Entity

**檔案**：`src/main/java/com/pk/support_ticket_api/notifications/domain/Notification.java`

```java
package com.pk.support_ticket_api.notifications.domain;

import com.pk.support_ticket_api.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "notifications")
public class Notification extends BaseEntity {

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    // ==================== Factory Methods ====================

    public static Notification create(
            UUID recipientId,
            UUID ticketId,
            NotificationType type,
            String title,
            String message
    ) {
        Notification notification = new Notification();
        notification.setRecipientId(recipientId);
        notification.setTicketId(ticketId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setIsRead(false);
        return notification;
    }

    public void markAsRead() {
        this.isRead = true;
    }
}
```

---

### Step 1.4：建立 NotificationRepository

**檔案**：`src/main/java/com/pk/support_ticket_api/notifications/repository/NotificationRepository.java`

```java
package com.pk.support_ticket_api.notifications.repository;

import com.pk.support_ticket_api.notifications.domain.Notification;
import com.pk.support_ticket_api.notifications.domain.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * 查詢使用者的通知列表
     */
    Page<Notification> findByRecipientIdOrderByCreatedAtDesc(
            UUID recipientId,
            Pageable pageable
    );

    /**
     * 查詢使用者的未讀通知列表
     */
    Page<Notification> findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(
            UUID recipientId,
            Pageable pageable
    );

    /**
     * 查詢使用者的未讀通知數量
     */
    long countByRecipientIdAndIsReadFalse(UUID recipientId);

    /**
     * 查詢單筆通知（驗證歸屬）
     */
    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    /**
     * 查詢特定 Ticket 的特定類型通知（用於 Email 發送）
     */
    List<Notification> findByTicketIdAndType(UUID ticketId, NotificationType type);

    /**
     * 標記所有通知為已讀
     */
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.recipientId = :recipientId AND n.isRead = false")
    int markAllAsRead(@Param("recipientId") UUID recipientId);
}
```

---

### Step 1.5：建立 AsyncConfig

**檔案**：`src/main/java/com/pk/support_ticket_api/common/config/AsyncConfig.java`

```java
package com.pk.support_ticket_api.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
@RequiredArgsConstructor
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    @Bean("notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("notification-");
        executor.setRejectedExecutionHandler(new LogRejectedExecutionHandler());
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return notificationExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new AsyncExceptionHandler();
    }

    private static class LogRejectedExecutionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("Notification task rejected due to queue overflow");
        }
    }

    private static class AsyncExceptionHandler implements AsyncUncaughtExceptionHandler {
        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            log.error("Async method {} threw exception", method.getName(), ex);
        }
    }
}
```

**注意**：需要加入 `import lombok.RequiredArgsConstructor;`

---

## Phase 2：通知核心功能

### Step 2.1：建立 NotificationResponse DTO

**檔案**：`src/main/java/com/pk/support_ticket_api/notifications/dto/NotificationResponse.java`

```java
package com.pk.support_ticket_api.notifications.dto;

import com.pk.support_ticket_api.notifications.domain.Notification;
import com.pk.support_ticket_api.notifications.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID ticketId,
        NotificationType type,
        String title,
        String message,
        Boolean isRead,
        Instant createdAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getTicketId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getIsRead(),
                notification.getCreatedAt()
        );
    }
}
```

---

### Step 2.2：建立 NotificationSummaryResponse DTO

**檔案**：`src/main/java/com/pk/support_ticket_api/notifications/dto/NotificationSummaryResponse.java`

```java
package com.pk.support_ticket_api.notifications.dto;

import com.pk.support_ticket_api.notifications.domain.Notification;
import com.pk.support_ticket_api.notifications.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationSummaryResponse(
        UUID id,
        UUID ticketId,
        NotificationType type,
        String title,
        String message,
        Boolean isRead,
        Instant createdAt
) {
    public static NotificationSummaryResponse from(Notification notification) {
        return new NotificationSummaryResponse(
                notification.getId(),
                notification.getTicketId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getIsRead(),
                notification.getCreatedAt()
        );
    }
}
```

**注意**：Summary 和 Response 目前相同，後續可擴展（如：Summary 不含 message）

---

### Step 2.3：建立 NotificationContent 通知內容範本

**檔案**：`src/main/java/com/pk/support_ticket_api/notifications/dto/NotificationContent.java`

```java
package com.pk.support_ticket_api.notifications.dto;

import com.pk.support_ticket_api.notifications.domain.NotificationType;
import com.pk.support_ticket_api.tickets.domain.Ticket;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record NotificationContent(
        String title,
        String message
) {
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

    public static NotificationContent forSlaWarning(Ticket ticket) {
        return new NotificationContent(
                "[SLA 警告] Ticket 即將逾期",
                String.format(
                        "Ticket「%s」預計於 %s 逾期，請儘早處理。",
                        ticket.getTitle(),
                        formatDeadline(ticket.getSlaDeadline())
                )
        );
    }

    public static NotificationContent forSlaBreach(Ticket ticket) {
        return new NotificationContent(
                "[SLA 逾期] Ticket 已逾期！",
                String.format(
                        "Ticket「%s」已於 %s 逾期，需要立即處理！",
                        ticket.getTitle(),
                        formatDeadline(ticket.getSlaDeadline())
                )
        );
    }

    private static String formatDeadline(java.time.Instant deadline) {
        if (deadline == null) {
            return "未知";
        }
        return FORMATTER.format(deadline);
    }
}
```

---

### Step 2.4：建立 NotificationService 介面

**檔案**：`src/main/java/com/pk/support_ticket_api/notifications/service/NotificationService.java`

```java
package com.pk.support_ticket_api.notifications.service;

import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.notifications.domain.NotificationType;
import com.pk.support_ticket_api.notifications.dto.NotificationResponse;
import com.pk.support_ticket_api.notifications.dto.NotificationSummaryResponse;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface NotificationService {

    /**
     * 發送 SLA 通知
     * @param ticket 逾期的 Ticket
     * @param type 通知類型（SLA_WARNING 或 SLA_BREACH）
     * @param recipientId 收件人 ID
     * @throws org.springframework.dao.DataIntegrityViolationException 若已發送過（Unique Index 衝突）
     */
    void sendSlaNotification(Ticket ticket, NotificationType type, UUID recipientId);

    /**
     * 查詢使用者的通知列表（分頁）
     */
    PageResponse<NotificationSummaryResponse> getNotifications(
            UUID recipientId,
            Pageable pageable,
            boolean unreadOnly
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

---

### Step 2.5：建立 NotificationServiceImpl

**檔案**：`src/main/java/com/pk/support_ticket_api/notifications/service/NotificationServiceImpl.java`

```java
package com.pk.support_ticket_api.notifications.service;

import com.pk.support_ticket_api.common.exception.ForbiddenOperationException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.notifications.domain.Notification;
import com.pk.support_ticket_api.notifications.domain.NotificationType;
import com.pk.support_ticket_api.notifications.dto.NotificationContent;
import com.pk.support_ticket_api.notifications.dto.NotificationResponse;
import com.pk.support_ticket_api.notifications.dto.NotificationSummaryResponse;
import com.pk.support_ticket_api.notifications.repository.NotificationRepository;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;

    @Override
    public void sendSlaNotification(Ticket ticket, NotificationType type, UUID recipientId) {
        NotificationContent content = buildContent(ticket, type);

        Notification notification = Notification.create(
                recipientId,
                ticket.getId(),
                type,
                content.title(),
                content.message()
        );

        notificationRepository.save(notification);
        log.info("Sent {} notification for ticket {} to user {}",
                type, ticket.getId(), recipientId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationSummaryResponse> getNotifications(
            UUID recipientId,
            Pageable pageable,
            boolean unreadOnly
    ) {
        Page<Notification> page;

        if (unreadOnly) {
            page = notificationRepository.findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(
                    recipientId, pageable);
        } else {
            page = notificationRepository.findByRecipientIdOrderByCreatedAtDesc(
                    recipientId, pageable);
        }

        return PageResponse.from(page, NotificationSummaryResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationResponse getNotificationById(UUID notificationId, UUID recipientId) {
        Notification notification = notificationRepository
                .findByIdAndRecipientId(notificationId, recipientId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification not found: " + notificationId));

        return NotificationResponse.from(notification);
    }

    @Override
    public void markAsRead(UUID notificationId, UUID recipientId) {
        Notification notification = notificationRepository
                .findByIdAndRecipientId(notificationId, recipientId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification not found: " + notificationId));

        notification.markAsRead();
        notificationRepository.save(notification);
    }

    @Override
    public void markAllAsRead(UUID recipientId) {
        notificationRepository.markAllAsRead(recipientId);
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID recipientId) {
        return notificationRepository.countByRecipientIdAndIsReadFalse(recipientId);
    }

    // ==================== Private Methods ====================

    private NotificationContent buildContent(Ticket ticket, NotificationType type) {
        return switch (type) {
            case SLA_WARNING -> NotificationContent.forSlaWarning(ticket);
            case SLA_BREACH -> NotificationContent.forSlaBreach(ticket);
            default -> throw new IllegalArgumentException("Unsupported type for SLA notification: " + type);
        };
    }
}
```

---

### Step 2.6：建立 NotificationController

**檔案**：`src/main/java/com/pk/support_ticket_api/notifications/web/NotificationController.java`

```java
package com.pk.support_ticket_api.notifications.web;

import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.notifications.dto.NotificationResponse;
import com.pk.support_ticket_api.notifications.dto.NotificationSummaryResponse;
import com.pk.support_ticket_api.notifications.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<PageResponse<NotificationSummaryResponse>> getNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            CurrentUser currentUser
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        PageResponse<NotificationSummaryResponse> response =
                notificationService.getNotifications(currentUser.userId(), pageable, unreadOnly);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<NotificationResponse> getNotificationById(
            @PathVariable UUID id,
            CurrentUser currentUser
    ) {
        NotificationResponse response =
                notificationService.getNotificationById(id, currentUser.userId());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(CurrentUser currentUser) {
        long count = notificationService.getUnreadCount(currentUser.userId());
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable UUID id,
            CurrentUser currentUser
    ) {
        notificationService.markAsRead(id, currentUser.userId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(CurrentUser currentUser) {
        notificationService.markAllAsRead(currentUser.userId());
        return ResponseEntity.noContent().build();
    }
}
```

---

### Step 2.7：修改 SecurityConfig 開放端點

**檔案**：`src/main/java/com/pk/support_ticket_api/common/config/SecurityConfig.java`

在現有 `@Bean public SecurityFilterChain` 中新增 `/api/v1/notifications/**` 的 permitAll 設定：

```java
.requestMatchers("/api/v1/notifications/**").permitAll()
```

**注意**：權限驗證由 Controller 層的 `CurrentUser` 參數處理

---

## Phase 3：SLA 監控功能

### Step 3.1：建立 TicketSlaSpecification

**檔案**：`src/main/java/com/pk/support_ticket_api/sla/domain/TicketSlaSpecification.java`

```java
package com.pk.support_ticket_api.sla.domain;

import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import org.springframework.data.jpa.domain.Specification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class TicketSlaSpecification {

    private static final Duration WARNING_WINDOW = Duration.ofHours(2);

    private static final List<TicketStatus> ACTIVE_STATUSES = List.of(
            TicketStatus.OPEN,
            TicketStatus.IN_PROGRESS,
            TicketStatus.WAITING_ON_CUSTOMER
    );

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
                cb.isTrue(root.get("slaDeadline").isNotNull()),
                root.get("status").in(ACTIVE_STATUSES),
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
        Instant now = Instant.now();

        return (root, query, cb) -> cb.and(
                cb.isTrue(root.get("slaDeadline").isNotNull()),
                root.get("status").in(ACTIVE_STATUSES),
                cb.lessThan(root.get("slaDeadline"), now)
        );
    }
}
```

---

### Step 3.2：擴展 TicketRepository 新增 SLA 查詢方法

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/repository/TicketRepository.java`

在現有介面中新增方法：

```java
package com.pk.support_ticket_api.tickets.repository;

import com.pk.support_ticket_api.tickets.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TicketRepository extends
        JpaRepository<Ticket, UUID>,
        JpaSpecificationExecutor<Ticket> {

    // 現有方法保持不變
}
```

**注意**：`JpaSpecificationExecutor` 已存在，SLA 查詢將使用 Specification 模式

---

### Step 3.3：建立 SlaMonitoringService

**檔案**：`src/main/java/com/pk/support_ticket_api/sla/service/SlaMonitoringService.java`

```java
package com.pk.support_ticket_api.sla.service;

import com.pk.support_ticket_api.notifications.domain.NotificationType;
import com.pk.support_ticket_api.notifications.service.NotificationService;
import com.pk.support_ticket_api.sla.domain.TicketSlaSpecification;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class SlaMonitoringService {

    private final TicketRepository ticketRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    /**
     * 處理 SLA Breach（已逾期）
     */
    public void processSlaBreaches() {
        List<Ticket> breachedTickets = ticketRepository.findAll(
                TicketSlaSpecification.isSlaBreached()
        );

        log.info("Found {} tickets with SLA breach", breachedTickets.size());

        for (Ticket ticket : breachedTickets) {
            try {
                sendBreachNotifications(ticket);
            } catch (DataIntegrityViolationException e) {
                // Unique Index 衝突，代表已發送過，跳過
                log.debug("SLA breach notification already sent for ticket: {}", ticket.getId());
            } catch (Exception e) {
                log.error("Failed to process SLA breach for ticket: {}", ticket.getId(), e);
            }
        }
    }

    /**
     * 處理 SLA Warning（即將逾期）
     */
    public void processSlaWarnings() {
        List<Ticket> warningTickets = ticketRepository.findAll(
                TicketSlaSpecification.isApproachingSlaBreach()
        );

        log.info("Found {} tickets approaching SLA breach", warningTickets.size());

        for (Ticket ticket : warningTickets) {
            try {
                sendWarningNotifications(ticket);
            } catch (DataIntegrityViolationException e) {
                // Unique Index 衝突，代表已發送過，跳過
                log.debug("SLA warning notification already sent for ticket: {}", ticket.getId());
            } catch (Exception e) {
                log.error("Failed to process SLA warning for ticket: {}", ticket.getId(), e);
            }
        }
    }

    // ==================== Private Methods ====================

    private void sendBreachNotifications(Ticket ticket) {
        // 通知負責 Agent
        if (ticket.getAssignedTo() != null) {
            notificationService.sendSlaNotification(
                    ticket, NotificationType.SLA_BREACH, ticket.getAssignedTo()
            );
        }

        // 通知所有 Admin
        List<UUID> adminIds = userRepository.findAllAdminIds();
        for (UUID adminId : adminIds) {
            notificationService.sendSlaNotification(
                    ticket, NotificationType.SLA_BREACH, adminId
            );
        }

        log.info("Sent SLA BREACH notifications for ticket: {}", ticket.getId());
    }

    private void sendWarningNotifications(Ticket ticket) {
        // 只通知負責 Agent
        if (ticket.getAssignedTo() != null) {
            notificationService.sendSlaNotification(
                    ticket, NotificationType.SLA_WARNING, ticket.getAssignedTo()
            );
            log.info("Sent SLA WARNING notification for ticket: {} to agent: {}",
                    ticket.getId(), ticket.getAssignedTo());
        } else {
            log.debug("Ticket {} has no assignee, skipping SLA warning", ticket.getId());
        }
    }
}
```

---

### Step 3.4：擴展 UserRepository 新增查詢 Admin ID 方法

**檔案**：`src/main/java/com/pk/support_ticket_api/users/repository/UserRepository.java`

在現有介面中新增方法：

```java
package com.pk.support_ticket_api.users.repository;

import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.domain.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends
        JpaRepository<User, UUID>,
        JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    // 現有方法保持不變，新增以下方法：

    /**
     * 查詢所有 Admin 的 ID
     */
    @Query("SELECT u.id FROM User u WHERE u.role = 'ADMIN' AND u.status = 'ACTIVE'")
    List<UUID> findAllAdminIds();
}
```

---

### Step 3.5：建立 SlaMonitoringJob

**檔案**：`src/main/java/com/pk/support_ticket_api/sla/job/SlaMonitoringJob.java`

```java
package com.pk.support_ticket_api.sla.job;

import com.pk.support_ticket_api.sla.service.SlaMonitoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SlaMonitoringJob {

    private final SlaMonitoringService slaMonitoringService;

    /**
     * SLA Breach 檢查：每 15 分鐘執行
     */
    @Scheduled(cron = "0 */15 * * * *")
    public void checkSlaBreach() {
        log.info("Starting SLA breach check...");
        try {
            slaMonitoringService.processSlaBreaches();
        } catch (Exception e) {
            log.error("Error during SLA breach check", e);
        }
    }

    /**
     * SLA Warning 檢查：每 30 分鐘執行
     */
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

---

### Step 3.6：在主程式啟用排程

**檔案**：`src/main/java/com/pk/support_ticket_api/SupportTicketApiApplication.java`

確保有 `@EnableScheduling` 註解：

```java
package com.pk.support_ticket_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling
public class SupportTicketApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupportTicketApiApplication.class, args);
    }
}
```

---

## Phase 4：Email 通知

### Step 4.1：加入 Spring Mail 依賴

**檔案**：`pom.xml`

在 `<dependencies>` 中新增：

```xml
<!-- Email -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

---

### Step 4.2：設定 Email 環境變數

**檔案**：`.env`

新增以下設定（使用 MailHog）：

```bash
# Email Configuration (MailHog)
SPRING_MAIL_HOST=localhost
SPRING_MAIL_PORT=1025
SPRING_MAIL_USERNAME=
SPRING_MAIL_PASSWORD=
```

**注意**：MailHog 已在 `compose.yaml` 中設定，無需認證

---

### Step 4.3：建立 EmailNotificationService

**檔案**：`src/main/java/com/pk/support_ticket_api/sla/service/EmailNotificationService.java`

```java
package com.pk.support_ticket_api.sla.service;

import com.pk.support_ticket_api.notifications.domain.Notification;
import com.pk.support_ticket_api.notifications.domain.NotificationType;
import com.pk.support_ticket_api.notifications.repository.NotificationRepository;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailNotificationService {

    private final JavaMailSender mailSender;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    /**
     * 非同步發送 SLA Email 通知
     */
    @Async("notificationExecutor")
    public void sendSlaAlertEmail(java.util.UUID ticketId, NotificationType type) {
        log.debug("Sending SLA email for ticket: {}, type: {}", ticketId, type);

        List<Notification> notifications = notificationRepository
                .findByTicketIdAndType(ticketId, type);

        for (Notification notification : notifications) {
            User recipient = userRepository.findById(notification.getRecipientId())
                    .orElse(null);

            if (recipient == null || recipient.getEmail() == null) {
                log.warn("Cannot send email: recipient or email is null for notification: {}",
                        notification.getId());
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

        log.info("Email sent successfully to: {}", to);
    }
}
```

---

### Step 4.4：整合 Email 發送至 SlaMonitoringService

修改 `SlaMonitoringService`，在發送通知後觸發 Email：

```java
// 在 sendBreachNotifications 方法中新增
private void sendBreachNotifications(Ticket ticket) {
    // ... 現有邏輯 ...

    // 觸發 Email（非同步）
    emailNotificationService.sendSlaAlertEmail(ticket.getId(), NotificationType.SLA_BREACH);
}

private void sendWarningNotifications(Ticket ticket) {
    // ... 現有邏輯 ...

    // 觸發 Email（非同步）
    emailNotificationService.sendSlaAlertEmail(ticket.getId(), NotificationType.SLA_WARNING);
}
```

**注意**：需要注入 `EmailNotificationService` 並處理循環依賴（使用 `@Lazy` 或重構架構）

---

## Phase 5：測試

### Step 5.1：建立 TicketSlaSpecificationTest

**檔案**：`src/test/java/com/pk/support_ticket_api/sla/domain/TicketSlaSpecificationTest.java`

```java
package com.pk.support_ticket_api.sla.domain;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TicketSlaSpecificationTest {

    @Test
    @DisplayName("Should find tickets approaching SLA breach (within 2 hours)")
    void shouldFindTicketsApproachingSlaBreach() {
        Ticket warningTicket = createTicket(
                Instant.now().plus(1, ChronoUnit.HOURS)  // 1 小時後逾期
        );

        Ticket breachTicket = createTicket(
                Instant.now().minus(1, ChronoUnit.HOURS)  // 已逾期
        );

        Ticket safeTicket = createTicket(
                Instant.now().plus(3, ChronoUnit.HOURS)  // 3 小時後才逾期
        );

        List<Ticket> result = filter(TicketSlaSpecification.isApproachingSlaBreach(),
                warningTicket, breachTicket, safeTicket);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(warningTicket);
    }

    @Test
    @DisplayName("Should find tickets with SLA breach")
    void shouldFindSlaBreachedTickets() {
        Ticket breachTicket = createTicket(
                Instant.now().minus(1, ChronoUnit.HOURS)
        );

        Ticket safeTicket = createTicket(
                Instant.now().plus(1, ChronoUnit.HOURS)
        );

        List<Ticket> result = filter(TicketSlaSpecification.isSlaBreached(),
                breachTicket, safeTicket);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(breachTicket);
    }

    @Test
    @DisplayName("Should exclude resolved tickets from SLA breach check")
    void shouldExcludeResolvedTickets() {
        Ticket resolvedTicket = createTicket(
                Instant.now().minus(1, ChronoUnit.HOURS)
        );
        resolvedTicket.setStatus(TicketStatus.RESOLVED);

        List<Ticket> result = filter(TicketSlaSpecification.isSlaBreached(), resolvedTicket);

        assertThat(result).isEmpty();
    }

    // Helper methods
    private Ticket createTicket(Instant slaDeadline) {
        Ticket ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setTitle("Test Ticket");
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setPriority(TicketPriority.MEDIUM);
        ticket.setSlaDeadline(slaDeadline);
        return ticket;
    }

    private List<Ticket> filter(
            org.springframework.data.jpa.domain.Specification<Ticket> spec,
            Ticket... tickets
    ) {
        // 使用 Mockito 模擬 Repository 或直接使用 Specification 測試
        return java.util.Arrays.stream(tickets)
                .filter(t -> spec.toPredicate(
                        new org.springframework.data.jpa.domain.Specification<Ticket>() {
                        }.toString() != null,
                        null, null))
                .toList();
    }
}
```

---

### Step 5.2：建立 NotificationServiceImplTest

**檔案**：`src/test/java/com/pk/support_ticket_api/notifications/service/NotificationServiceImplTest.java`

```java
package com.pk.support_ticket_api.notifications.service;

import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.notifications.domain.Notification;
import com.pk.support_ticket_api.notifications.domain.NotificationType;
import com.pk.support_ticket_api.notifications.dto.NotificationResponse;
import com.pk.support_ticket_api.notifications.dto.NotificationSummaryResponse;
import com.pk.support_ticket_api.notifications.repository.NotificationRepository;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private UUID recipientId;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        recipientId = UUID.randomUUID();
        ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setTitle("Test Ticket");
        ticket.setSlaDeadline(Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("Should send SLA notification")
    void shouldSendSlaNotification() {
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        notificationService.sendSlaNotification(ticket, NotificationType.SLA_BREACH, recipientId);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getRecipientId()).isEqualTo(recipientId);
        assertThat(saved.getTicketId()).isEqualTo(ticket.getId());
        assertThat(saved.getType()).isEqualTo(NotificationType.SLA_BREACH);
        assertThat(saved.getTitle()).contains("[SLA 逾期]");
    }

    @Test
    @DisplayName("Should get notifications for user")
    void shouldGetNotificationsForUser() {
        Notification notification = createNotification();
        Page<Notification> page = new PageImpl<>(List.of(notification));
        Pageable pageable = PageRequest.of(0, 20);

        when(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipientId, pageable))
                .thenReturn(page);

        PageResponse<NotificationSummaryResponse> result =
                notificationService.getNotifications(recipientId, pageable, false);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).id()).isEqualTo(notification.getId());
    }

    @Test
    @DisplayName("Should mark notification as read")
    void shouldMarkAsRead() {
        Notification notification = createNotification();
        notification.setIsRead(false);

        when(notificationRepository.findByIdAndRecipientId(notification.getId(), recipientId))
                .thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        notificationService.markAsRead(notification.getId(), recipientId);

        assertThat(notification.getIsRead()).isTrue();
        verify(notificationRepository).save(notification);
    }

    @Test
    @DisplayName("Should throw exception when notification not found")
    void shouldThrowWhenNotificationNotFound() {
        UUID notificationId = UUID.randomUUID();

        when(notificationRepository.findByIdAndRecipientId(notificationId, recipientId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                notificationService.getNotificationById(notificationId, recipientId)
        ).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Should get unread count")
    void shouldGetUnreadCount() {
        when(notificationRepository.countByRecipientIdAndIsReadFalse(recipientId))
                .thenReturn(5L);

        long count = notificationService.getUnreadCount(recipientId);

        assertThat(count).isEqualTo(5);
    }

    // Helper methods
    private Notification createNotification() {
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID());
        notification.setRecipientId(recipientId);
        notification.setTicketId(ticket.getId());
        notification.setType(NotificationType.SLA_BREACH);
        notification.setTitle("[SLA 逾期] Ticket 已逾期！");
        notification.setMessage("Ticket Test Ticket 已於 2024-01-15 10:00 逾期");
        notification.setIsRead(false);
        notification.setCreatedAt(Instant.now());
        return notification;
    }
}
```

---

### Step 5.3：建立 SlaMonitoringServiceTest

**檔案**：`src/test/java/com/pk/support_ticket_api/sla/service/SlaMonitoringServiceTest.java`

```java
package com.pk.support_ticket_api.sla.service;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.notifications.domain.NotificationType;
import com.pk.support_ticket_api.notifications.service.NotificationService;
import com.pk.support_ticket_api.sla.domain.TicketSlaSpecification;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlaMonitoringServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailNotificationService emailNotificationService;

    @InjectMocks
    private SlaMonitoringService slaMonitoringService;

    private Ticket breachedTicket;
    private Ticket warningTicket;
    private UUID adminId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();

        // 逾期 Ticket
        breachedTicket = new Ticket();
        breachedTicket.setId(UUID.randomUUID());
        breachedTicket.setTitle("Breached Ticket");
        breachedTicket.setStatus(TicketStatus.OPEN);
        breachedTicket.setPriority(TicketPriority.HIGH);
        breachedTicket.setAssignedTo(UUID.randomUUID());
        breachedTicket.setSlaDeadline(Instant.now().minus(1, ChronoUnit.HOURS));

        // 即將逾期 Ticket
        warningTicket = new Ticket();
        warningTicket.setId(UUID.randomUUID());
        warningTicket.setTitle("Warning Ticket");
        warningTicket.setStatus(TicketStatus.IN_PROGRESS);
        warningTicket.setPriority(TicketPriority.MEDIUM);
        warningTicket.setAssignedTo(UUID.randomUUID());
        warningTicket.setSlaDeadline(Instant.now().plus(1, ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("Should process SLA breaches and send notifications")
    void shouldProcessSlaBreaches() {
        when(ticketRepository.findAll(TicketSlaSpecification.isSlaBreached()))
                .thenReturn(List.of(breachedTicket));
        when(userRepository.findAllAdminIds())
                .thenReturn(List.of(adminId));

        slaMonitoringService.processSlaBreaches();

        // 驗證發送通知給 Agent 和 Admin
        verify(notificationService, times(2)).sendSlaNotification(
                eq(breachedTicket),
                eq(NotificationType.SLA_BREACH),
                any(UUID.class)
        );
    }

    @Test
    @DisplayName("Should skip already notified tickets (Unique Index conflict)")
    void shouldSkipAlreadyNotifiedTickets() {
        when(ticketRepository.findAll(TicketSlaSpecification.isSlaBreached()))
                .thenReturn(List.of(breachedTicket));
        when(userRepository.findAllAdminIds())
                .thenReturn(List.of(adminId));

        // 第一次發送成功，第二次拋出衝突異常
        doNothing().doThrow(new DataIntegrityViolationException("Unique constraint"))
                .when(notificationService).sendSlaNotification(any(), any(), any());

        slaMonitoringService.processSlaBreaches();

        // 驗證有嘗試發送（衝突被 catch）
        verify(notificationService, atLeast(1)).sendSlaNotification(
                eq(breachedTicket),
                eq(NotificationType.SLA_BREACH),
                any(UUID.class)
        );
    }

    @Test
    @DisplayName("Should only notify assigned agent for SLA warnings")
    void shouldOnlyNotifyAgentForWarnings() {
        when(ticketRepository.findAll(TicketSlaSpecification.isApproachingSlaBreach()))
                .thenReturn(List.of(warningTicket));

        slaMonitoringService.processSlaWarnings();

        // 驗證只發送一次通知（只給 Agent）
        verify(notificationService, times(1)).sendSlaNotification(
                eq(warningTicket),
                eq(NotificationType.SLA_WARNING),
                eq(warningTicket.getAssignedTo())
        );
    }

    @Test
    @DisplayName("Should handle ticket without assignee for warnings")
    void shouldHandleTicketWithoutAssignee() {
        warningTicket.setAssignedTo(null);

        when(ticketRepository.findAll(TicketSlaSpecification.isApproachingSlaBreach()))
                .thenReturn(List.of(warningTicket));

        slaMonitoringService.processSlaWarnings();

        // 驗證沒有發送通知
        verify(notificationService, never()).sendSlaNotification(
                any(), eq(NotificationType.SLA_WARNING), any()
        );
    }
}
```

---

## 實作完成檢查清單

### Phase 1 完成標記

- [ ] V8__create_notifications_table.sql 已建立並執行成功
- [ ] NotificationType.java 已建立
- [ ] Notification.java Entity 已建立
- [ ] NotificationRepository.java 已建立
- [ ] AsyncConfig.java 已建立

### Phase 2 完成標記

- [ ] NotificationResponse.java 已建立
- [ ] NotificationSummaryResponse.java 已建立
- [ ] NotificationContent.java 已建立
- [ ] NotificationService.java 介面已建立
- [ ] NotificationServiceImpl.java 已建立
- [ ] NotificationController.java 已建立
- [ ] SecurityConfig.java 已更新

### Phase 3 完成標記

- [ ] TicketSlaSpecification.java 已建立
- [ ] TicketRepository.java 已更新
- [ ] SlaMonitoringService.java 已建立
- [ ] UserRepository.java 已更新
- [ ] SlaMonitoringJob.java 已建立
- [ ] SupportTicketApiApplication.java 已啟用 @EnableScheduling

### Phase 4 完成標記

- [ ] pom.xml 已加入 spring-boot-starter-mail
- [ ] .env 已設定 MailHog
- [ ] EmailNotificationService.java 已建立
- [ ] SlaMonitoringService 已整合 Email 發送

### Phase 5 完成標記

- [ ] TicketSlaSpecificationTest.java 已建立
- [ ] NotificationServiceImplTest.java 已建立
- [ ] SlaMonitoringServiceTest.java 已建立
- [ ] 所有測試通過

---

## 預期產出檔案清單

### 新建檔案（14 個）

| 檔案 | Phase |
|------|-------|
| `V8__create_notifications_table.sql` | 1.1 |
| `NotificationType.java` | 1.2 |
| `Notification.java` | 1.3 |
| `NotificationRepository.java` | 1.4 |
| `AsyncConfig.java` | 1.5 |
| `NotificationResponse.java` | 2.1 |
| `NotificationSummaryResponse.java` | 2.2 |
| `NotificationContent.java` | 2.3 |
| `NotificationService.java` | 2.4 |
| `NotificationServiceImpl.java` | 2.5 |
| `NotificationController.java` | 2.6 |
| `TicketSlaSpecification.java` | 3.1 |
| `SlaMonitoringService.java` | 3.3 |
| `SlaMonitoringJob.java` | 3.5 |
| `EmailNotificationService.java` | 4.3 |
| `TicketSlaSpecificationTest.java` | 5.1 |
| `NotificationServiceImplTest.java` | 5.2 |
| `SlaMonitoringServiceTest.java` | 5.3 |

### 修改檔案（4 個）

| 檔案 | 修改內容 |
|------|----------|
| `pom.xml` | 加入 spring-boot-starter-mail |
| `SecurityConfig.java` | 開放 /api/v1/notifications/** |
| `TicketRepository.java` | 新增 SLA 查詢方法（使用 Specification） |
| `UserRepository.java` | 新增 findAllAdminIds() 方法 |
| `SupportTicketApiApplication.java` | 加入 @EnableScheduling |
| `.env` | 加入 MailHog 設定 |
