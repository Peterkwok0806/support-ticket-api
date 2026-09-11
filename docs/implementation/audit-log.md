# Audit Log 實作步驟

## 文件資訊

| 項目 | 內容 |
|------|------|
| 版本 | v1.1 |
| 日期 | 2026-09-11 |
| 狀態 | 待實作 |
| 更新 | 加入 Phase 4 Unit Test（必做）|

---

## Phase 1：基礎設施（Foundation）

### Step 1.1：修改 AuditAction.java — 移除 REOPENED

**檔案位置**：`src/main/java/com/pk/support_ticket_api/common/domain/enums/AuditAction.java`

**修改內容**：移除 `REOPENED` 枚舉值

```java
package com.pk.support_ticket_api.common.domain.enums;

public enum AuditAction {
    TICKET_CREATED,
    STATUS_CHANGED,
    PRIORITY_CHANGED,
    ASSIGNED,
    UNASSIGNED,
    COMMENT_ADDED,
    ATTACHMENT_ADDED,  // 預留，尚未實作
    RESOLVED,
    CLOSED
    // REOPENED 已移除
}
```

---

### Step 1.2：建立 AuditFieldName.java 枚舉

**檔案位置**：`src/main/java/com/pk/support_ticket_api/audit/domain/AuditFieldName.java`

**新建內容**：

```java
package com.pk.support_ticket_api.audit.domain;

public enum AuditFieldName {
    STATUS,
    PRIORITY,
    ASSIGNEE
}
```

---

### Step 1.3：建立 AuditLog.java Entity

**檔案位置**：`src/main/java/com/pk/support_ticket_api/audit/domain/AuditLog.java`

**新建內容**：

```java
package com.pk.support_ticket_api.audit.domain;

import com.pk.support_ticket_api.common.domain.BaseEntity;
import com.pk.support_ticket_api.common.domain.enums.AuditAction;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "audit_logs")
public class AuditLog extends BaseEntity {

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_name", length = 30)
    private AuditFieldName fieldName;

    @Column(name = "old_value", length = 500)
    private String oldValue;

    @Column(name = "new_value", length = 500)
    private String newValue;

    @Column(name = "internal")
    private Boolean internal;

    // ==================== Factory Methods ====================

    public static AuditLog create(UUID actorId, UUID ticketId, AuditAction action) {
        AuditLog log = new AuditLog();
        log.setActorId(actorId);
        log.setTicketId(ticketId);
        log.setAction(action);
        return log;
    }

    public static AuditLog createFieldChange(
            UUID actorId,
            UUID ticketId,
            AuditAction action,
            AuditFieldName fieldName,
            String oldValue,
            String newValue
    ) {
        AuditLog log = create(actorId, ticketId, action);
        log.setFieldName(fieldName);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        return log;
    }

    public static AuditLog createCommentAdded(
            UUID actorId,
            UUID ticketId,
            boolean internal
    ) {
        AuditLog log = create(actorId, ticketId, AuditAction.COMMENT_ADDED);
        log.setInternal(internal);
        return log;
    }
}
```

---

### Step 1.4：建立 AuditLogRepository.java

**檔案位置**：`src/main/java/com/pk/support_ticket_api/audit/repository/AuditLogRepository.java`

**新建內容**：

```java
package com.pk.support_ticket_api.audit.repository;

import com.pk.support_ticket_api.audit.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByTicketIdOrderByCreatedAtDesc(UUID ticketId, Pageable pageable);
}
```

---

### Step 1.5：建立 Migration 檔案

**檔案位置**：`src/main/resources/db/migration/V7__create_audit_logs_table.sql`

**新建內容**：

```sql
-- Audit Log 稽核日誌表
-- 用於記錄 Ticket 的所有重要操作，支援可追溯性與可稽核性

CREATE TABLE audit_logs (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id        UUID            NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    ticket_id       UUID            NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    action          VARCHAR(30)     NOT NULL,
    field_name      VARCHAR(30),
    old_value       VARCHAR(500),
    new_value       VARCHAR(500),
    internal        BOOLEAN,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- 註解
COMMENT ON TABLE audit_logs IS '操作稽核日誌，記錄所有重要操作';
COMMENT ON COLUMN audit_logs.actor_id IS '執行操作的使用者 ID';
COMMENT ON COLUMN audit_logs.ticket_id IS '操作發生的 Ticket ID';
COMMENT ON COLUMN audit_logs.action IS '操作類型（TICKET_CREATED/STATUS_CHANGED/PRIORITY_CHANGED/ASSIGNED/UNASSIGNED/COMMENT_ADDED/RESOLVED/CLOSED）';
COMMENT ON COLUMN audit_logs.field_name IS '變更的欄位名稱（STATUS/PRIORITY/ASSIGNEE），非欄位變更時可為空';
COMMENT ON COLUMN audit_logs.old_value IS '變更前的值';
COMMENT ON COLUMN audit_logs.new_value IS '變更後的值';
COMMENT ON COLUMN audit_logs.internal IS '是否為內部操作，用於 COMMENT_ADDED 事件';
COMMENT ON COLUMN audit_logs.created_at IS '操作發生的時間';

-- 索引設計
CREATE INDEX idx_audit_logs_ticket_id ON audit_logs(ticket_id);
CREATE INDEX idx_audit_logs_actor_id ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
CREATE INDEX idx_audit_logs_ticket_created ON audit_logs(ticket_id, created_at DESC);
```

---

## Phase 2：查詢 API（Query API）

### Step 2.1：建立 AuditLogResponse.java DTO

**檔案位置**：`src/main/java/com/pk/support_ticket_api/audit/dto/AuditLogResponse.java`

**新建內容**：

```java
package com.pk.support_ticket_api.audit.dto;

import com.pk.support_ticket_api.audit.domain.AuditFieldName;
import com.pk.support_ticket_api.audit.domain.AuditLog;
import com.pk.support_ticket_api.common.domain.enums.AuditAction;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
    UUID id,
    UUID actorId,
    String actorName,
    UUID ticketId,
    AuditAction action,
    AuditFieldName fieldName,
    String oldValue,
    String newValue,
    Boolean internal,
    Instant createdAt
) {

    public static AuditLogResponse from(AuditLog auditLog) {
        return new AuditLogResponse(
            auditLog.getId(),
            auditLog.getActorId(),
            null,  // actorName 由 Service 層填充
            auditLog.getTicketId(),
            auditLog.getAction(),
            auditLog.getFieldName(),
            auditLog.getOldValue(),
            auditLog.getNewValue(),
            auditLog.getInternal(),
            auditLog.getCreatedAt()
        );
    }
}
```

---

### Step 2.2：建立 AuditLogService.java 介面

**檔案位置**：`src/main/java/com/pk/support_ticket_api/audit/service/AuditLogService.java`

**新建內容**：

```java
package com.pk.support_ticket_api.audit.service;

import com.pk.support_ticket_api.audit.dto.AuditLogResponse;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.common.security.CurrentUser;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface AuditLogService {

    PageResponse<AuditLogResponse> getAuditLogsByTicketId(
        UUID ticketId,
        Pageable pageable,
        CurrentUser currentUser
    );
}
```

---

### Step 2.3：建立 AuditLogServiceImpl.java 實作

**檔案位置**：`src/main/java/com/pk/support_ticket_api/audit/service/AuditLogServiceImpl.java`

**新建內容**：

```java
package com.pk.support_ticket_api.audit.service;

import com.pk.support_ticket_api.audit.domain.AuditLog;
import com.pk.support_ticket_api.audit.dto.AuditLogResponse;
import com.pk.support_ticket_api.audit.repository.AuditLogRepository;
import com.pk.support_ticket_api.common.exception.ForbiddenOperationException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;

    @Override
    public PageResponse<AuditLogResponse> getAuditLogsByTicketId(
            UUID ticketId,
            Pageable pageable,
            CurrentUser currentUser
    ) {
        Ticket ticket = ticketRepository.findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Ticket not found: " + ticketId));

        if (!hasReadPermission(ticket, currentUser)) {
            throw new ForbiddenOperationException(
                "No permission to view audit logs for this ticket");
        }

        Page<AuditLog> page = auditLogRepository.findByTicketIdOrderByCreatedAtDesc(
            ticketId, pageable);

        return PageResponse.from(page, this::enrichResponse);
    }

    private boolean hasReadPermission(Ticket ticket, CurrentUser currentUser) {
        return switch (currentUser.role()) {
            case "ADMIN" -> true;
            case "AGENT" -> ticket.getAssignedTo() != null
                    && ticket.getAssignedTo().equals(currentUser.userId());
            case "CUSTOMER" -> ticket.getCreatedBy().equals(currentUser.userId());
            default -> false;
        };
    }

    private AuditLogResponse enrichResponse(AuditLog auditLog) {
        AuditLogResponse response = AuditLogResponse.from(auditLog);

        String actorName = userRepository.findById(auditLog.getActorId())
            .map(User::getDisplayName)
            .orElse(null);

        return new AuditLogResponse(
            response.id(),
            response.actorId(),
            actorName,
            response.ticketId(),
            response.action(),
            response.fieldName(),
            response.oldValue(),
            response.newValue(),
            response.internal(),
            response.createdAt()
        );
    }
}
```

---

### Step 2.4：建立 TicketAuditLogController.java

**檔案位置**：`src/main/java/com/pk/support_ticket_api/audit/web/TicketAuditLogController.java`

**新建內容**：

```java
package com.pk.support_ticket_api.audit.web;

import com.pk.support_ticket_api.audit.dto.AuditLogResponse;
import com.pk.support_ticket_api.audit.service.AuditLogService;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Audit Logs", description = "稽核日誌 API")
public class TicketAuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "查詢 Ticket 的稽核日誌")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PageResponse<AuditLogResponse>> findAll(
            @PathVariable UUID ticketId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        PageResponse<AuditLogResponse> response =
            auditLogService.getAuditLogsByTicketId(ticketId, pageable, currentUser);
        return ResponseEntity.ok(response);
    }
}
```

**注意**：需確認是否需要在 Controller 加入 `import org.springframework.data.domain.Sort;` 和 `import org.springframework.security.access.prepost.PreAuthorize;`

---

## Phase 3：寫入整合（Write Integration）

### Step 3.1：修改 TicketServiceImpl.java — 注入 AuditLogRepository

**檔案位置**：`src/main/java/com/pk/support_ticket_api/tickets/service/TicketServiceImpl.java`

**修改 1：在 class 中增加 AuditLogRepository 注入**

```java
@Service
@RequiredArgsConstructor
@Transactional
public class TicketServiceImpl implements TicketService {

    private final TicketRepository ticketRepository;
    private final AuditLogRepository auditLogRepository;  // 新增
    // ... 其他 fields
```

**修改 2：在 createTicket() 方法中加入 Audit Log 寫入**

在 `ticketRepository.save(ticket)` 之後、return 之前加入：

```java
@Override
public TicketResponse createTicket(CreateTicketRequest request, UUID createdBy) {
    // ... 現有邏輯（建立 Ticket 並保存）

    Ticket saved = ticketRepository.save(ticket);

    // 記錄 Audit Log（同一 Transaction）
    auditLogRepository.save(AuditLog.create(
        createdBy,
        saved.getId(),
        AuditAction.TICKET_CREATED
    ));

    return enrichResponse(saved);
}
```

**修改 3：在 updateStatus() 方法中加入 Audit Log 寫入**

在 `ticket.setStatus(request.status())` 之後、return 之前加入：

```java
@Override
public TicketResponse updateStatus(UUID id, TicketStatusUpdateRequest request, CurrentUser currentUser) {
    Ticket ticket = findTicketById(id);

    // ... 現有驗證邏輯

    TicketStatus oldStatus = ticket.getStatus();
    ticket.setStatus(request.status());
    handleStatusSideEffects(ticket, request.status());

    // 記錄 Audit Log
    AuditLog audit = AuditLog.createFieldChange(
        currentUser.userId(),
        ticket.getId(),
        AuditAction.STATUS_CHANGED,
        AuditFieldName.STATUS,
        oldStatus.name(),
        request.status().name()
    );
    auditLogRepository.save(audit);

    Ticket saved = ticketRepository.save(ticket);
    return enrichResponse(saved);
}
```

**修改 4：在 updateTicket() 方法中加入 Priority 變更的 Audit Log**

在 priority 變更邏輯處加入：

```java
@Override
public TicketResponse updateTicket(UUID id, UpdateTicketRequest request) {
    Ticket ticket = findTicketById(id);

    // ... 現有邏輯

    if (request.priority() != null && request.priority() != ticket.getPriority()) {
        TicketPriority oldPriority = ticket.getPriority();  // 新增：保存舊值
        ticket.setPriority(request.priority());
        recalculateSlaDeadline(ticket);

        // 記錄 Audit Log
        auditLogRepository.save(AuditLog.createFieldChange(
            currentUser.userId(),  // 注意：updateTicket() 目前沒有 currentUser 參數
            ticket.getId(),
            AuditAction.PRIORITY_CHANGED,
            AuditFieldName.PRIORITY,
            oldPriority.name(),
            request.priority().name()
        ));
    }

    Ticket saved = ticketRepository.save(ticket);
    return enrichResponse(saved);
}
```

**注意**：`updateTicket()` 方法需要調整以取得 currentUser。由於目前方法簽章是 `updateTicket(UUID id, UpdateTicketRequest request)`，需要修改為包含 `CurrentUser` 參數，或者從 Spring Security Context 取得當前用戶。

**修改 5：在 assignTicket() 方法中加入 Audit Log 寫入**

```java
@Override
public TicketResponse assignTicket(UUID id, TicketAssignRequest request) {
    Ticket ticket = findTicketById(id);

    // ... 現有驗證邏輯

    UUID oldAssigneeId = ticket.getAssignedTo();
    ticket.setAssignedTo(request.assigneeId());

    // 記錄 Audit Log
    if (request.assigneeId() != null) {
        auditLogRepository.save(AuditLog.createFieldChange(
            currentUser.userId(),
            ticket.getId(),
            AuditAction.ASSIGNED,
            AuditFieldName.ASSIGNEE,
            oldAssigneeId != null ? oldAssigneeId.toString() : null,
            request.assigneeId().toString()
        ));
    } else if (oldAssigneeId != null) {
        auditLogRepository.save(AuditLog.create(
            currentUser.userId(),
            ticket.getId(),
            AuditAction.UNASSIGNED
        ));
    }

    Ticket saved = ticketRepository.save(ticket);
    return enrichResponse(saved);
}
```

**注意**：`assignTicket()` 方法同樣需要調整以取得 currentUser。

**需要的 import**：

```java
import com.pk.support_ticket_api.audit.domain.AuditLog;
import com.pk.support_ticket_api.audit.domain.AuditFieldName;
import com.pk.support_ticket_api.audit.repository.AuditLogRepository;
import com.pk.support_ticket_api.common.domain.enums.AuditAction;
```

---

### Step 3.2：修改 CommentServiceImpl.java — 加入 Audit Log

**檔案位置**：`src/main/java/com/pk/support_ticket_api/comments/service/CommentServiceImpl.java`

**修改 1：在 class 中增加 AuditLogRepository 注入**

```java
@Service
@RequiredArgsConstructor
@Transactional
public class CommentServiceImpl implements CommentService {

    private final CommentRepository commentRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;  // 新增
```

**修改 2：在 createComment() 方法中加入 Audit Log 寫入**

在 `Comment saved = commentRepository.save(comment)` 之後、return 之前加入：

```java
@Override
public CommentResponse createComment(
        UUID ticketId,
        CreateCommentRequest request,
        CurrentUser currentUser
) {
    // ... 現有邏輯

    Comment saved = commentRepository.save(comment);

    // 記錄 Audit Log
    auditLogRepository.save(AuditLog.createCommentAdded(
        currentUser.userId(),
        ticketId,
        isInternal
    ));

    return enrichResponse(saved);
}
```

**需要的 import**：

```java
import com.pk.support_ticket_api.audit.domain.AuditLog;
import com.pk.support_ticket_api.audit.repository.AuditLogRepository;
```

---

## Phase 4：Unit Test（必做）

### Step 4.1：AuditAction 枚舉完整性測試

**檔案位置**：`src/test/java/com/pk/support_ticket_api/audit/domain/AuditActionTest.java`

**新建內容**：

```java
package com.pk.support_ticket_api.audit.domain;

import com.pk.support_ticket_api.common.domain.enums.AuditAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuditAction 枚舉完整性測試")
class AuditActionTest {

    @Test
    @DisplayName("AuditAction 應包含所有預期的事件")
    void shouldContainAllExpectedActions() {
        // Given
        Set<String> expectedActions = Set.of(
            "TICKET_CREATED",
            "STATUS_CHANGED",
            "PRIORITY_CHANGED",
            "ASSIGNED",
            "UNASSIGNED",
            "COMMENT_ADDED",
            "ATTACHMENT_ADDED",
            "RESOLVED",
            "CLOSED"
        );

        // When
        Set<String> actualActions = Arrays.stream(AuditAction.values())
            .map(Enum::name)
            .collect(Collectors.toSet());

        // Then
        assertThat(actualActions)
            .containsExactlyInAnyOrderElementsOf(expectedActions);
    }

    @Test
    @DisplayName("AuditAction 不應包含 REOPENED")
    void shouldNotContainReopened() {
        // Given & When
        boolean hasReopened = Arrays.stream(AuditAction.values())
            .anyMatch(a -> a.name().equals("REOPENED"));

        // Then
        assertThat(hasReopened).isFalse();
    }

    @Test
    @DisplayName("AuditAction 枚舉值數量應為 9")
    void shouldHaveNineValues() {
        // Given & When
        int count = AuditAction.values().length;

        // Then
        assertThat(count).isEqualTo(9);
    }
}
```

---

### Step 4.2：AuditLogService 記錄正確性測試

**檔案位置**：`src/test/java/com/pk/support_ticket_api/audit/service/AuditLogServiceTest.java`

**新建內容**：

```java
package com.pk.support_ticket_api.audit.service;

import com.pk.support_ticket_api.audit.domain.AuditLog;
import com.pk.support_ticket_api.audit.dto.AuditLogResponse;
import com.pk.support_ticket_api.audit.repository.AuditLogRepository;
import com.pk.support_ticket_api.common.domain.enums.AuditAction;
import com.pk.support_ticket_api.common.exception.ForbiddenOperationException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
@DisplayName("AuditLogService 單元測試")
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AuditLogServiceImpl auditLogService;

    private UUID ticketId;
    private UUID customerId;
    private UUID agentId;
    private UUID adminId;
    private Ticket testTicket;

    @BeforeEach
    void setUp() {
        ticketId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        adminId = UUID.randomUUID();

        testTicket = new Ticket();
        testTicket.setId(ticketId);
        testTicket.setCreatedBy(customerId);
    }

    @Nested
    @DisplayName("getAuditLogsByTicketId 權限測試")
    class PermissionTests {

        @Test
        @DisplayName("Customer 查詢自己建立的 Ticket 應成功")
        void customerCanQueryOwnTicket() {
            // Given
            CurrentUser customer = new CurrentUser(customerId, "customer@test.com", "CUSTOMER");
            Pageable pageable = PageRequest.of(0, 20);
            AuditLog auditLog = createAuditLog(AuditAction.TICKET_CREATED);
            Page<AuditLog> page = new PageImpl<>(List.of(auditLog));

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(testTicket));
            when(auditLogRepository.findByTicketIdOrderByCreatedAtDesc(ticketId, pageable)).thenReturn(page);
            when(userRepository.findById(customerId)).thenReturn(Optional.of(createUser(customerId)));

            // When
            PageResponse<AuditLogResponse> response = auditLogService.getAuditLogsByTicketId(
                ticketId, pageable, customer);

            // Then
            assertThat(response.content()).hasSize(1);
            verify(auditLogRepository).findByTicketIdOrderByCreatedAtDesc(ticketId, pageable);
        }

        @Test
        @DisplayName("Customer 查詢他人 Ticket 應拋出 ForbiddenOperationException")
        void customerCannotQueryOthersTicket() {
            // Given
            CurrentUser customer = new CurrentUser(UUID.randomUUID(), "other@test.com", "CUSTOMER");
            Pageable pageable = PageRequest.of(0, 20);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(testTicket));

            // When/Then
            assertThatThrownBy(() -> auditLogService.getAuditLogsByTicketId(ticketId, pageable, customer))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("No permission to view audit logs");
        }

        @Test
        @DisplayName("Agent 查詢被指派的 Ticket 應成功")
        void agentCanQueryAssignedTicket() {
            // Given
            testTicket.setAssignedTo(agentId);
            CurrentUser agent = new CurrentUser(agentId, "agent@test.com", "AGENT");
            Pageable pageable = PageRequest.of(0, 20);
            AuditLog auditLog = createAuditLog(AuditAction.STATUS_CHANGED);
            Page<AuditLog> page = new PageImpl<>(List.of(auditLog));

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(testTicket));
            when(auditLogRepository.findByTicketIdOrderByCreatedAtDesc(ticketId, pageable)).thenReturn(page);
            when(userRepository.findById(agentId)).thenReturn(Optional.of(createUser(agentId)));

            // When
            PageResponse<AuditLogResponse> response = auditLogService.getAuditLogsByTicketId(
                ticketId, pageable, agent);

            // Then
            assertThat(response.content()).hasSize(1);
        }

        @Test
        @DisplayName("Agent 查詢未被指派的 Ticket 應拋出 ForbiddenOperationException")
        void agentCannotQueryUnassignedTicket() {
            // Given
            testTicket.setAssignedTo(UUID.randomUUID());  // 指派給別人
            CurrentUser agent = new CurrentUser(agentId, "agent@test.com", "AGENT");
            Pageable pageable = PageRequest.of(0, 20);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(testTicket));

            // When/Then
            assertThatThrownBy(() -> auditLogService.getAuditLogsByTicketId(ticketId, pageable, agent))
                .isInstanceOf(ForbiddenOperationException.class);
        }

        @Test
        @DisplayName("Admin 查詢任何 Ticket 應成功")
        void adminCanQueryAnyTicket() {
            // Given
            CurrentUser admin = new CurrentUser(adminId, "admin@test.com", "ADMIN");
            Pageable pageable = PageRequest.of(0, 20);
            AuditLog auditLog = createAuditLog(AuditAction.COMMENT_ADDED);
            Page<AuditLog> page = new PageImpl<>(List.of(auditLog));

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(testTicket));
            when(auditLogRepository.findByTicketIdOrderByCreatedAtDesc(ticketId, pageable)).thenReturn(page);
            when(userRepository.findById(adminId)).thenReturn(Optional.of(createUser(adminId)));

            // When
            PageResponse<AuditLogResponse> response = auditLogService.getAuditLogsByTicketId(
                ticketId, pageable, admin);

            // Then
            assertThat(response.content()).hasSize(1);
        }

        @Test
        @DisplayName("Ticket 不存在應拋出 ResourceNotFoundException")
        void ticketNotFoundShouldThrowException() {
            // Given
            UUID nonExistentTicketId = UUID.randomUUID();
            CurrentUser admin = new CurrentUser(adminId, "admin@test.com", "ADMIN");
            Pageable pageable = PageRequest.of(0, 20);

            when(ticketRepository.findById(nonExistentTicketId)).thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> auditLogService.getAuditLogsByTicketId(nonExistentTicketId, pageable, admin))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket not found");
        }
    }

    @Nested
    @DisplayName("getAuditLogsByTicketId 回應格式測試")
    class ResponseFormatTests {

        @Test
        @DisplayName("應正確填充 actorName")
        void shouldEnrichActorName() {
            // Given
            CurrentUser admin = new CurrentUser(adminId, "admin@test.com", "ADMIN");
            Pageable pageable = PageRequest.of(0, 20);

            AuditLog auditLog = createAuditLog(AuditAction.TICKET_CREATED);
            auditLog.setActorId(adminId);
            Page<AuditLog> page = new PageImpl<>(List.of(auditLog));

            User actor = createUser(adminId);
            actor.setDisplayName("Test Admin");

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(testTicket));
            when(auditLogRepository.findByTicketIdOrderByCreatedAtDesc(ticketId, pageable)).thenReturn(page);
            when(userRepository.findById(adminId)).thenReturn(Optional.of(actor));

            // When
            PageResponse<AuditLogResponse> response = auditLogService.getAuditLogsByTicketId(
                ticketId, pageable, admin);

            // Then
            assertThat(response.content().get(0).actorName()).isEqualTo("Test Admin");
        }
    }

    // ==================== Helper Methods ====================

    private AuditLog createAuditLog(AuditAction action) {
        AuditLog log = new AuditLog();
        log.setId(UUID.randomUUID());
        log.setActorId(customerId);
        log.setTicketId(ticketId);
        log.setAction(action);
        log.setCreatedAt(Instant.now());
        return log;
    }

    private User createUser(UUID userId) {
        User user = new User();
        user.setId(userId);
        user.setDisplayName("Test User");
        return user;
    }
}
```

---

### Step 4.3：TicketService Audit Log 寫入測試

**檔案位置**：`src/test/java/com/pk/support_ticket_api/tickets/service/TicketServiceAuditTest.java`

**新建內容**：

```java
package com.pk.support_ticket_api.tickets.service;

import com.pk.support_ticket_api.audit.domain.AuditFieldName;
import com.pk.support_ticket_api.audit.domain.AuditLog;
import com.pk.support_ticket_api.audit.repository.AuditLogRepository;
import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.categories.repository.CategoryRepository;
import com.pk.support_ticket_api.categories.service.SlaCalculator;
import com.pk.support_ticket_api.common.domain.enums.AuditAction;
import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.common.exception.ForbiddenOperationException;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.dto.*;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TicketService Audit Log 寫入測試")
class TicketServiceAuditTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private TicketStateMachine stateMachine;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SlaCalculator slaCalculator;

    @Mock
    private Clock clock;

    @InjectMocks
    private TicketServiceImpl ticketService;

    @Captor
    private ArgumentCaptor<AuditLog> auditLogCaptor;

    private UUID customerId;
    private UUID agentId;
    private UUID categoryId;
    private Category testCategory;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        categoryId = UUID.randomUUID();

        testCategory = new Category();
        testCategory.setId(categoryId);
        testCategory.setName("Technical Support");
        testCategory.setSlaHours(24);

        // Mock clock
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-11T10:00:00Z"), ZoneId.of("UTC"));
        when(clock.instant()).thenReturn(fixedClock.instant());
        when(clock.getZone()).thenReturn(fixedClock.getZone());
    }

    @Nested
    @DisplayName("createTicket 應寫入 TICKET_CREATED")
    class CreateTicketAuditTests {

        @Test
        @DisplayName("建立 Ticket 時應寫入 TICKET_CREATED AuditLog")
        void createTicketShouldWriteAuditLog() {
            // Given
            CreateTicketRequest request = new CreateTicketRequest(
                "Test Title",
                "Test Description",
                categoryId,
                TicketPriority.MEDIUM
            );

            when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(testCategory));
            when(slaCalculator.calculateSlaDueAt(any(), any(), any())).thenReturn(Instant.now());
            when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> {
                Ticket ticket = invocation.getArgument(0);
                ticket.setId(UUID.randomUUID());
                return ticket;
            });

            // When
            ticketService.createTicket(request, customerId);

            // Then
            verify(auditLogRepository).save(auditLogCaptor.capture());
            AuditLog savedLog = auditLogCaptor.getValue();

            assertThat(savedLog.getActorId()).isEqualTo(customerId);
            assertThat(savedLog.getAction()).isEqualTo(AuditAction.TICKET_CREATED);
            assertThat(savedLog.getOldValue()).isNull();
            assertThat(savedLog.getNewValue()).isNull();
        }

        @Test
        @DisplayName("createTicket 失敗時不應寫入 AuditLog")
        void createTicketFailureShouldNotWriteAuditLog() {
            // Given
            CreateTicketRequest request = new CreateTicketRequest(
                "Test Title",
                "Test Description",
                categoryId,
                TicketPriority.MEDIUM
            );

            when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> ticketService.createTicket(request, customerId))
                .isInstanceOf(Exception.class);

            // AuditLog 不應被寫入
            verify(auditLogRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateStatus 應寫入 STATUS_CHANGED")
    class UpdateStatusAuditTests {

        @Test
        @DisplayName("變更狀態時應寫入 STATUS_CHANGED AuditLog")
        void updateStatusShouldWriteAuditLog() {
            // Given
            UUID ticketId = UUID.randomUUID();
            Ticket ticket = createTestTicket(ticketId, TicketStatus.OPEN);
            CurrentUser agent = new CurrentUser(agentId, "agent@test.com", "AGENT");

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.IN_PROGRESS);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(stateMachine.canTransition(TicketStatus.OPEN, TicketStatus.IN_PROGRESS)).thenReturn(true);

            Ticket savedTicket = createTestTicket(ticketId, TicketStatus.IN_PROGRESS);
            when(ticketRepository.save(any(Ticket.class))).thenReturn(savedTicket);

            // When
            ticketService.updateStatus(ticketId, request, agent);

            // Then
            verify(auditLogRepository).save(auditLogCaptor.capture());
            AuditLog savedLog = auditLogCaptor.getValue();

            assertThat(savedLog.getActorId()).isEqualTo(agentId);
            assertThat(savedLog.getAction()).isEqualTo(AuditAction.STATUS_CHANGED);
            assertThat(savedLog.getFieldName()).isEqualTo(AuditFieldName.STATUS);
            assertThat(savedLog.getOldValue()).isEqualTo("OPEN");
            assertThat(savedLog.getNewValue()).isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("非 Agent/Admin 無法變更狀態")
        void customerCannotChangeStatus() {
            // Given
            UUID ticketId = UUID.randomUUID();
            Ticket ticket = createTestTicket(ticketId, TicketStatus.OPEN);
            CurrentUser customer = new CurrentUser(customerId, "customer@test.com", "CUSTOMER");

            TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.IN_PROGRESS);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            // When/Then
            assertThatThrownBy(() -> ticketService.updateStatus(ticketId, request, customer))
                .isInstanceOf(ForbiddenOperationException.class);

            verify(auditLogRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("assignTicket 應寫入 ASSIGNED/UNASSIGNED")
    class AssignTicketAuditTests {

        @Test
        @DisplayName("指派 Agent 時應寫入 ASSIGNED AuditLog")
        void assignAgentShouldWriteAssignedAuditLog() {
            // Given
            UUID ticketId = UUID.randomUUID();
            UUID newAgentId = UUID.randomUUID();
            Ticket ticket = createTestTicket(ticketId, TicketStatus.OPEN);
            CurrentUser admin = new CurrentUser(UUID.randomUUID(), "admin@test.com", "ADMIN");

            TicketAssignRequest request = new TicketAssignRequest(newAgentId);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(userRepository.existsById(newAgentId)).thenReturn(true);

            Ticket savedTicket = createTestTicket(ticketId, TicketStatus.OPEN);
            savedTicket.setAssignedTo(newAgentId);
            when(ticketRepository.save(any(Ticket.class))).thenReturn(savedTicket);

            // When
            ticketService.assignTicket(ticketId, request, admin);

            // Then
            verify(auditLogRepository).save(auditLogCaptor.capture());
            AuditLog savedLog = auditLogCaptor.getValue();

            assertThat(savedLog.getAction()).isEqualTo(AuditAction.ASSIGNED);
            assertThat(savedLog.getFieldName()).isEqualTo(AuditFieldName.ASSIGNEE);
            assertThat(savedLog.getOldValue()).isNull();
            assertThat(savedLog.getNewValue()).isEqualTo(newAgentId.toString());
        }

        @Test
        @DisplayName("取消指派時應寫入 UNASSIGNED AuditLog")
        void unassignShouldWriteUnassignedAuditLog() {
            // Given
            UUID ticketId = UUID.randomUUID();
            Ticket ticket = createTestTicket(ticketId, TicketStatus.OPEN);
            ticket.setAssignedTo(agentId);  // 原本有指派

            CurrentUser admin = new CurrentUser(UUID.randomUUID(), "admin@test.com", "ADMIN");
            TicketAssignRequest request = new TicketAssignRequest(null);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            Ticket savedTicket = createTestTicket(ticketId, TicketStatus.OPEN);
            savedTicket.setAssignedTo(null);
            when(ticketRepository.save(any(Ticket.class))).thenReturn(savedTicket);

            // When
            ticketService.assignTicket(ticketId, request, admin);

            // Then
            verify(auditLogRepository).save(auditLogCaptor.capture());
            AuditLog savedLog = auditLogCaptor.getValue();

            assertThat(savedLog.getAction()).isEqualTo(AuditAction.UNASSIGNED);
        }
    }

    // ==================== Helper Methods ====================

    private Ticket createTestTicket(UUID ticketId, TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setId(ticketId);
        ticket.setTitle("Test Ticket");
        ticket.setDescription("Test Description");
        ticket.setStatus(status);
        ticket.setPriority(TicketPriority.MEDIUM);
        ticket.setCategoryId(categoryId);
        ticket.setCreatedBy(customerId);
        ticket.setCreatedAt(Instant.now());
        return ticket;
    }
}
```

---

### Step 4.4：執行測試

```bash
./mvnw test -Dtest=AuditActionTest,TicketServiceAuditTest,AuditLogServiceTest
```

確認所有測試通過。

---

## Phase 5：驗證（Verification）

### Step 5.1：執行 Migration

```bash
./mvnw flyway:migrate
```

確認 `audit_logs` 表格已正確建立。

### Step 5.2：執行編譯

```bash
./mvnw compile
```

確認所有程式碼編譯通過。

### Step 5.3：執行所有測試

```bash
./mvnw test
```

確認所有測試通過。

### Step 5.4：手動測試（可選）

使用 Swagger UI 或 Postman 測試：

1. 以 Customer 身份建立 Ticket → 確認 Audit Log 寫入
2. 以 Agent 身份變更狀態 → 確認 Audit Log 寫入 + oldValue/newValue
3. 以 Agent 身份新增 Comment（internal=true） → 確認 Audit Log 寫入 + internal=true
4. 以 Customer 身份查詢 Audit Log → 確認權限過濾正確

---

## 實作檢查清單

### Phase 1：基礎設施

- [ ] Step 1.1：修改 AuditAction.java — 移除 REOPENED
- [ ] Step 1.2：建立 AuditFieldName.java 枚舉
- [ ] Step 1.3：建立 AuditLog.java Entity（含工廠方法）
- [ ] Step 1.4：建立 AuditLogRepository.java
- [ ] Step 1.5：建立 V7__create_audit_logs_table.sql

### Phase 2：查詢 API

- [ ] Step 2.1：建立 AuditLogResponse.java DTO
- [ ] Step 2.2：建立 AuditLogService.java 介面
- [ ] Step 2.3：建立 AuditLogServiceImpl.java 實作
- [ ] Step 2.4：建立 TicketAuditLogController.java

### Phase 3：寫入整合

- [ ] Step 3.1.1：修改 TicketServiceImpl — 注入 AuditLogRepository
- [ ] Step 3.1.2：修改 createTicket() — 記錄 TICKET_CREATED
- [ ] Step 3.1.3：修改 updateStatus() — 記錄 STATUS_CHANGED
- [ ] Step 3.1.4：修改 updateTicket() — 記錄 PRIORITY_CHANGED
- [ ] Step 3.1.5：修改 assignTicket() — 記錄 ASSIGNED/UNASSIGNED
- [ ] Step 3.2：修改 CommentServiceImpl — 記錄 COMMENT_ADDED

### Phase 4：Unit Test（必做）

- [ ] Step 4.1：建立 AuditActionTest.java — 枚舉完整性測試
- [ ] Step 4.2：建立 AuditLogServiceTest.java — Service 記錄正確性測試
- [ ] Step 4.3：建立 TicketServiceAuditTest.java — TicketService Audit 寫入測試
- [ ] Step 4.4：執行 Unit Test

### Phase 5：驗證

- [ ] Step 5.1：執行 Migration
- [ ] Step 5.2：執行編譯
- [ ] Step 5.3：執行所有測試

---

## 預期產出檔案

### 新增檔案（12 個）

```
src/main/java/com/pk/support_ticket_api/audit/
├── domain/
│   ├── AuditLog.java
│   └── AuditFieldName.java
├── dto/
│   └── AuditLogResponse.java
├── repository/
│   └── AuditLogRepository.java
├── service/
│   ├── AuditLogService.java
│   └── AuditLogServiceImpl.java
└── web/
    └── TicketAuditLogController.java

src/main/resources/db/migration/
└── V7__create_audit_logs_table.sql

src/test/java/com/pk/support_ticket_api/audit/
├── domain/
│   └── AuditActionTest.java                    # 枚舉完整性測試
├── service/
│   └── AuditLogServiceTest.java                # Service 記錄正確性測試
└── tickets/service/
    └── TicketServiceAuditTest.java             # TicketService Audit 寫入測試
```

### 修改檔案（3 個）

```
src/main/java/com/pk/support_ticket_api/
├── common/domain/enums/AuditAction.java        # 移除 REOPENED
├── tickets/service/TicketServiceImpl.java     # 加入 Audit Log 寫入
└── comments/service/CommentServiceImpl.java   # 加入 Audit Log 寫入
```

---

*實作步驟版本：v1.1 | 2026-09-11 | 含必做 Unit Test*
