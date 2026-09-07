# Ticket 實作計劃書

## 1. 概述

本計劃書定義 Ticket 核心功能的實作範圍、步驟、程式碼結構與驗收標準。

**參考文件**：
- [需求規格](../requirements/ticket.md)
- [架構說明](../architecture/ticket.md)

**預計工期**：6 個 Step（每個 Step 為獨立的可測試單元）

---

## 2. 實作前準備

### 2.1 資料庫調整

現有 `V1__create_initial_schema.sql` 中的 tickets 表格結構需調整：

| 變更 | 說明 |
|------|------|
| 新增 `closed_at` | 記錄關閉時間 |
| 新增 `first_response_at` | 記錄首次回應時間 |
| 新增 `sla_deadline` | SLA 截止時間 |
| 新增 `version` | 樂觀鎖版本欄位 |
| 調整 `status CHECK` | 移除 `WAITING_ON_CUSTOMER`，改為 `RESOLVED` |

**新增 Migration 檔案**：`V5__create_tickets_table.sql`

```sql
-- 移除既有 tickets 表格並重建
DROP TABLE IF EXISTS tickets CASCADE;

CREATE TABLE tickets (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title           VARCHAR(200) NOT NULL,
    description     TEXT,
    status          VARCHAR(20)  NOT NULL DEFAULT 'OPEN'
                    CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    priority        VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM'
                    CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    category_id     UUID         NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    created_by      UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    assigned_to     UUID         REFERENCES users(id) ON DELETE SET NULL,
    resolved_at     TIMESTAMPTZ,
    closed_at       TIMESTAMPTZ,
    first_response_at TIMESTAMPTZ,
    sla_deadline    TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE tickets IS '客戶支援工單';
COMMENT ON COLUMN tickets.version IS '樂觀鎖版本，併發更新衝突時拋出 OptimisticLockException';

CREATE INDEX idx_tickets_status ON tickets(status);
CREATE INDEX idx_tickets_priority ON tickets(priority);
CREATE INDEX idx_tickets_category_id ON tickets(category_id);
CREATE INDEX idx_tickets_assigned_to ON tickets(assigned_to);
CREATE INDEX idx_tickets_created_by ON tickets(created_by);
CREATE INDEX idx_tickets_sla_deadline ON tickets(sla_deadline) WHERE sla_deadline IS NOT NULL;
```

> **注意**：需確認無實際資料後方可執行刪除重建。若有資料，需規劃資料遷移。

---

## 3. 實作步驟

### Step 1：Domain 層

#### 1.1 Ticket Entity

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/domain/Ticket.java`

```java
package com.pk.support_ticket_api.tickets.domain;

import com.pk.support_ticket_api.common.domain.VersionedEntity;
import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "tickets")
public class Ticket extends VersionedEntity {

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TicketStatus status = TicketStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private TicketPriority priority = TicketPriority.MEDIUM;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "first_response_at")
    private Instant firstResponseAt;

    @Column(name = "sla_deadline")
    private Instant slaDeadline;
}
```

#### 1.2 Ticket State Machine

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/service/TicketStateMachine.java`

```java
package com.pk.support_ticket_api.tickets.service;

import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Ticket 狀態機。
 * 定義所有合法的狀態轉換規則。
 */
@Component
public class TicketStateMachine {

    private static final Map<TicketStatus, Set<TicketStatus>> TRANSITIONS;

    static {
        TRANSITIONS = new EnumMap<>(TicketStatus.class);
        TRANSITIONS.put(TicketStatus.OPEN, EnumSet.of(
            TicketStatus.IN_PROGRESS,
            TicketStatus.CLOSED
        ));
        TRANSITIONS.put(TicketStatus.IN_PROGRESS, EnumSet.of(
            TicketStatus.OPEN,
            TicketStatus.RESOLVED
        ));
        TRANSITIONS.put(TicketStatus.RESOLVED, EnumSet.of(
            TicketStatus.OPEN,
            TicketStatus.CLOSED
        ));
        TRANSITIONS.put(TicketStatus.CLOSED, EnumSet.noneOf(TicketStatus.class)); // 終態
    }

    /**
     * 驗證狀態轉換是否合法。
     *
     * @param from 當前狀態
     * @param to   目標狀態
     * @return true if 允許轉換
     */
    public boolean canTransition(TicketStatus from, TicketStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<TicketStatus> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * 取得指定狀態允許的所有轉換目標。
     *
     * @param from 當前狀態
     * @return 允許的目標狀態集合
     */
    public Set<TicketStatus> getAllowedTransitions(TicketStatus from) {
        if (from == null) {
            return EnumSet.noneOf(TicketStatus.class);
        }
        return TRANSITIONS.getOrDefault(from, EnumSet.noneOf(TicketStatus.class));
    }

    /**
     * 檢查是否為終態。
     *
     * @param status 狀態
     * @return true if 為終態（CLOSED）
     */
    public boolean isFinalState(TicketStatus status) {
        return status == TicketStatus.CLOSED;
    }
}
```

#### 1.3 Ticket Specification

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/domain/TicketSpecification.java`

```java
package com.pk.support_ticket_api.tickets.domain;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TicketSpecification {

    private TicketSpecification() {
    }

    public static Specification<Ticket> withFilters(
            List<TicketStatus> statuses,
            TicketPriority priority,
            UUID categoryId,
            UUID assignedTo,
            UUID createdBy,
            String keyword
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }

            if (priority != null) {
                predicates.add(cb.equal(root.get("priority"), priority));
            }

            if (categoryId != null) {
                predicates.add(cb.equal(root.get("categoryId"), categoryId));
            }

            if (assignedTo != null) {
                predicates.add(cb.equal(root.get("assignedTo"), assignedTo));
            }

            if (createdBy != null) {
                predicates.add(cb.equal(root.get("createdBy"), createdBy));
            }

            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword.toLowerCase() + "%";
                predicates.add(cb.like(
                    cb.lower(root.get("title")), pattern));
            }

            // 避免重複 count query
            query.distinct(true);

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
```

---

### Step 2：Repository 層

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/repository/TicketRepository.java`

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
}
```

---

### Step 3：DTO 層

#### 3.1 CreateTicketRequest

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/dto/CreateTicketRequest.java`

```java
package com.pk.support_ticket_api.tickets.dto;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTicketRequest(
    @NotBlank(message = "標題為必填")
    @Size(min = 1, max = 200, message = "標題長度需 1-200 字元")
    String title,

    @Size(max = 10000, message = "描述最大 10000 字元")
    String description,

    @NotNull(message = "分類為必填")
    UUID categoryId,

    TicketPriority priority  // 可選，預設 MEDIUM
) {}
```

#### 3.2 UpdateTicketRequest

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/dto/UpdateTicketRequest.java`

```java
package com.pk.support_ticket_api.tickets.dto;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateTicketRequest(
    @Size(min = 1, max = 200, message = "標題長度需 1-200 字元")
    String title,

    @Size(max = 10000, message = "描述最大 10000 字元")
    String description,

    UUID categoryId,

    TicketPriority priority
) {}
```

#### 3.3 TicketStatusUpdateRequest

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/dto/TicketStatusUpdateRequest.java`

```java
package com.pk.support_ticket_api.tickets.dto;

import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import jakarta.validation.constraints.NotNull;

public record TicketStatusUpdateRequest(
    @NotNull(message = "狀態為必填")
    TicketStatus status
) {}
```

#### 3.4 TicketAssignRequest

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/dto/TicketAssignRequest.java`

```java
package com.pk.support_ticket_api.tickets.dto;

import jakarta.validation.constraints.Null;

public record TicketAssignRequest(
    @Null(message = "指派 ID 格式錯誤，請使用 null 表示取消指派")
    UUID assigneeId  // null = 取消指派
) {}
```

#### 3.5 TicketResponse

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/dto/TicketResponse.java`

```java
package com.pk.support_ticket_api.tickets.dto;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.tickets.domain.Ticket;

import java.time.Instant;
import java.util.UUID;

public record TicketResponse(
    UUID id,
    String title,
    String description,
    TicketStatus status,
    TicketPriority priority,
    CategorySummaryResponse category,
    String createdByName,
    String assignedToName,
    Instant slaDeadline,
    Instant resolvedAt,
    Instant closedAt,
    Instant firstResponseAt,
    Instant createdAt,
    Instant updatedAt
) {
    public static TicketResponse from(Ticket ticket) {
        // 關聯資料需由 Service 層查詢後填充
        return new TicketResponse(
            ticket.getId(),
            ticket.getTitle(),
            ticket.getDescription(),
            ticket.getStatus(),
            ticket.getPriority(),
            null, // category
            null, // createdByName
            null, // assignedToName
            ticket.getSlaDeadline(),
            ticket.getResolvedAt(),
            ticket.getClosedAt(),
            ticket.getFirstResponseAt(),
            ticket.getCreatedAt(),
            ticket.getUpdatedAt()
        );
    }
}
```

#### 3.6 TicketSummaryResponse

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/dto/TicketSummaryResponse.java`

```java
package com.pk.support_ticket_api.tickets.dto;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.tickets.domain.Ticket;

import java.time.Instant;
import java.util.UUID;

public record TicketSummaryResponse(
    UUID id,
    String title,
    TicketStatus status,
    TicketPriority priority,
    String categoryName,
    String assignedToName,
    Instant slaDeadline,
    Instant createdAt
) {
    public static TicketSummaryResponse from(Ticket ticket) {
        return new TicketSummaryResponse(
            ticket.getId(),
            ticket.getTitle(),
            ticket.getStatus(),
            ticket.getPriority(),
            null, // categoryName
            null, // assignedToName
            ticket.getSlaDeadline(),
            ticket.getCreatedAt()
        );
    }
}
```

#### 3.7 TicketFilterRequest

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/dto/TicketFilterRequest.java`

```java
package com.pk.support_ticket_api.tickets.dto;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;

import java.util.List;
import java.util.UUID;

public record TicketFilterRequest(
    List<TicketStatus> statuses,
    TicketPriority priority,
    UUID categoryId,
    UUID assignedTo,
    UUID createdBy,
    String keyword
) {}
```

---

### Step 4：Service 層

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/service/TicketService.java`

```java
package com.pk.support_ticket_api.tickets.service;

import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.tickets.dto.*;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TicketService {

    TicketResponse createTicket(CreateTicketRequest request, UUID createdBy);

    TicketResponse getTicketById(UUID id);

    PageResponse<TicketSummaryResponse> getTickets(
        TicketFilterRequest filter,
        Pageable pageable
    );

    TicketResponse updateTicket(UUID id, UpdateTicketRequest request);

    TicketResponse updateStatus(UUID id, TicketStatusUpdateRequest request);

    TicketResponse assignTicket(UUID id, TicketAssignRequest request);

    void deleteTicket(UUID id);
}
```

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/service/TicketServiceImpl.java`

```java
package com.pk.support_ticket_api.tickets.service;

import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.categories.repository.CategoryRepository;
import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.common.exception.BusinessRuleException;
import com.pk.support_ticket_api.common.exception.ForbiddenOperationException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.domain.TicketSpecification;
import com.pk.support_ticket_api.tickets.dto.*;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class TicketServiceImpl implements TicketService {

    private final TicketRepository ticketRepository;
    private final TicketStateMachine stateMachine;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Override
    public TicketResponse createTicket(CreateTicketRequest request, UUID createdBy) {
        // 1. 驗證 Category 存在
        Category category = categoryRepository.findById(request.categoryId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Category not found: " + request.categoryId()));

        // 2. 計算 SLA deadline
        Instant slaDeadline = calculateSlaDeadline(category, request.priority());

        // 3. 建立 Entity
        Ticket ticket = new Ticket();
        ticket.setTitle(request.title());
        ticket.setDescription(request.description());
        ticket.setCategoryId(request.categoryId());
        ticket.setCreatedBy(createdBy);
        ticket.setPriority(request.priority() != null ? request.priority() : TicketPriority.MEDIUM);
        ticket.setSlaDeadline(slaDeadline);
        ticket.setStatus(TicketStatus.OPEN);

        Ticket saved = ticketRepository.save(ticket);
        return enrichResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public TicketResponse getTicketById(UUID id) {
        Ticket ticket = findTicketById(id);
        return enrichResponse(ticket);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TicketSummaryResponse> getTickets(
            TicketFilterRequest filter,
            Pageable pageable
    ) {
        Page<Ticket> page = ticketRepository.findAll(
            TicketSpecification.withFilters(
                filter.statuses(),
                filter.priority(),
                filter.categoryId(),
                filter.assignedTo(),
                filter.createdBy(),
                filter.keyword()
            ),
            pageable
        );

        return PageResponse.from(page, TicketSummaryResponse::from);
    }

    @Override
    public TicketResponse updateTicket(UUID id, UpdateTicketRequest request) {
        Ticket ticket = findTicketById(id);

        if (stateMachine.isFinalState(ticket.getStatus())) {
            throw new ForbiddenOperationException("Cannot update closed ticket");
        }

        if (request.title() != null && !request.title().isBlank()) {
            ticket.setTitle(request.title());
        }

        if (request.description() != null) {
            ticket.setDescription(request.description());
        }

        if (request.categoryId() != null) {
            validateCategoryExists(request.categoryId());
            ticket.setCategoryId(request.categoryId());
        }

        if (request.priority() != null && request.priority() != ticket.getPriority()) {
            ticket.setPriority(request.priority());
            recalculateSlaDeadline(ticket);
        }

        Ticket saved = ticketRepository.save(ticket);
        return enrichResponse(saved);
    }

    @Override
    public TicketResponse updateStatus(UUID id, TicketStatusUpdateRequest request) {
        Ticket ticket = findTicketById(id);

        // 狀態機驗證
        if (!stateMachine.canTransition(ticket.getStatus(), request.status())) {
            throw new ForbiddenOperationException(String.format(
                "Cannot transition from %s to %s",
                ticket.getStatus(),
                request.status()
            ));
        }

        // 執行轉換
        ticket.setStatus(request.status());

        // 處理副作用
        handleStatusSideEffects(ticket, request.status());

        Ticket saved = ticketRepository.save(ticket);
        return enrichResponse(saved);
    }

    @Override
    public TicketResponse assignTicket(UUID id, TicketAssignRequest request) {
        Ticket ticket = findTicketById(id);

        if (stateMachine.isFinalState(ticket.getStatus())) {
            throw new ForbiddenOperationException("Cannot assign closed ticket");
        }

        if (request.assigneeId() != null) {
            validateUserExists(request.assigneeId());
        }

        ticket.setAssignedTo(request.assigneeId());

        Ticket saved = ticketRepository.save(ticket);
        return enrichResponse(saved);
    }

    @Override
    public void deleteTicket(UUID id) {
        Ticket ticket = findTicketById(id);
        ticketRepository.delete(ticket);
    }

    // === Private Methods ===

    private Ticket findTicketById(UUID id) {
        return ticketRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Ticket not found: " + id));
    }

    private void validateCategoryExists(UUID categoryId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new BusinessRuleException("Category not found: " + categoryId);
        }
    }

    private void validateUserExists(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessRuleException("User not found: " + userId);
        }
    }

    private Instant calculateSlaDeadline(Category category, TicketPriority priority) {
        TicketPriority p = priority != null ? priority : TicketPriority.MEDIUM;
        int hours = switch (p) {
            case LOW -> category.getSlaHoursLow();
            case MEDIUM -> category.getSlaHoursMedium();
            case HIGH -> category.getSlaHoursHigh();
            case URGENT -> category.getSlaHoursUrgent();
        };
        return Instant.now(clock).plusSeconds(hours * 3600L);
    }

    private void recalculateSlaDeadline(Ticket ticket) {
        Category category = categoryRepository.findById(ticket.getCategoryId())
            .orElseThrow(() -> new BusinessRuleException("Category not found"));
        ticket.setSlaDeadline(calculateSlaDeadline(category, ticket.getPriority()));
    }

    private void handleStatusSideEffects(Ticket ticket, TicketStatus newStatus) {
        Instant now = Instant.now(clock);

        switch (newStatus) {
            case RESOLVED -> ticket.setResolvedAt(now);
            case CLOSED -> ticket.setClosedAt(now);
            case IN_PROGRESS -> {
                if (ticket.getFirstResponseAt() == null) {
                    ticket.setFirstResponseAt(now);
                }
            }
            default -> { /* 無副作用 */ }
        }
    }

    private TicketResponse enrichResponse(Ticket ticket) {
        TicketResponse response = TicketResponse.from(ticket);

        // 查詢關聯資料
        Map<UUID, Category> categories = categoryRepository.findAllById(
            List.of(ticket.getCategoryId())
        ).stream().collect(Collectors.toMap(Category::getId, Function.identity()));

        if (ticket.getAssignedTo() != null) {
            userRepository.findById(ticket.getAssignedTo())
                .ifPresent(user -> {
                    // 填充 assignedToName
                });
        }

        if (ticket.getCreatedBy() != null) {
            userRepository.findById(ticket.getCreatedBy())
                .ifPresent(user -> {
                    // 填充 createdByName
                });
        }

        return response;
    }
}
```

> **注意**：`enrichResponse` 需完整實作，可參考 CategoryResponse 的 from pattern 或使用 JOIN FETCH 一次查詢。

---

### Step 5：Controller 層

#### 5.1 TicketController

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/web/TicketController.java`

```java
package com.pk.support_ticket_api.tickets.web;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.tickets.dto.*;
import com.pk.support_ticket_api.tickets.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
@Tag(name = "Tickets", description = "工單管理 API")
public class TicketController {

    private final TicketService ticketService;

    @PostMapping
    @Operation(summary = "建立工單", description = "建立新的支援工單")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "成功建立"),
        @ApiResponse(responseCode = "400", description = "請求驗證失敗"),
        @ApiResponse(responseCode = "404", description = "分類不存在")
    })
    public ResponseEntity<TicketResponse> create(
            @Valid @RequestBody CreateTicketRequest request,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        TicketResponse response = ticketService.createTicket(
            request, currentUser.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "取得工單詳情", description = "依 ID 取得工單完整資訊")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功取得"),
        @ApiResponse(responseCode = "404", description = "工單不存在")
    })
    public ResponseEntity<TicketResponse> getById(@PathVariable UUID id) {
        TicketResponse response = ticketService.getTicketById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(summary = "查詢工單列表", description = "分頁查詢工單，支援多重過濾條件")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功取得列表")
    })
    public ResponseEntity<PageResponse<TicketSummaryResponse>> findAll(
            @RequestParam(required = false) List<TicketStatus> statuses,
            @RequestParam(required = false) TicketPriority priority,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID assignedTo,
            @RequestParam(required = false) UUID createdBy,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        TicketFilterRequest filter = new TicketFilterRequest(
            statuses, priority, categoryId, assignedTo, createdBy, keyword);
        PageResponse<TicketSummaryResponse> response = ticketService.getTickets(filter, pageable);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "更新工單", description = "更新工單基本資訊（標題、描述、分類、優先級）")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功更新"),
        @ApiResponse(responseCode = "400", description = "請求驗證失敗"),
        @ApiResponse(responseCode = "404", description = "工單或分類不存在"),
        @ApiResponse(responseCode = "409", description = "工單已關閉，無法更新")
    })
    public ResponseEntity<TicketResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTicketRequest request
    ) {
        TicketResponse response = ticketService.updateTicket(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "變更工單狀態", description = "變更工單狀態（需符合狀態機規則）")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功變更"),
        @ApiResponse(responseCode = "404", description = "工單不存在"),
        @ApiResponse(responseCode = "409", description = "不允許的狀態轉換")
    })
    public ResponseEntity<TicketResponse> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody TicketStatusUpdateRequest request
    ) {
        TicketResponse response = ticketService.updateStatus(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/assign")
    @Operation(summary = "指派工單", description = "指派工單給客服人員（傳入 null 取消指派）")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功指派"),
        @ApiResponse(responseCode = "404", description = "工單或用戶不存在"),
        @ApiResponse(responseCode = "409", description = "工單已關閉，無法指派")
    })
    public ResponseEntity<TicketResponse> assign(
            @PathVariable UUID id,
            @Valid @RequestBody TicketAssignRequest request
    ) {
        TicketResponse response = ticketService.assignTicket(id, request);
        return ResponseEntity.ok(response);
    }
}
```

#### 5.2 TicketAdminController

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/web/TicketAdminController.java`

```java
package com.pk.support_ticket_api.tickets.web;

import com.pk.support_ticket_api.tickets.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/tickets")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Tickets", description = "管理者工單管理 API")
public class TicketAdminController {

    private final TicketService ticketService;

    @DeleteMapping("/{id}")
    @Operation(summary = "刪除工單", description = "刪除指定工單（管理員專用）")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "成功刪除"),
        @ApiResponse(responseCode = "404", description = "工單不存在")
    })
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ticketService.deleteTicket(id);
        return ResponseEntity.noContent().build();
    }
}
```

---

### Step 6：Exception 與測試

#### 6.1 TicketException

**檔案**：`src/main/java/com/pk/support_ticket_api/tickets/exception/TicketException.java`

```java
package com.pk.support_ticket_api.tickets.exception;

public class TicketException extends RuntimeException {

    public TicketException(String message) {
        super(message);
    }
}
```

#### 6.2 單元測試

**TicketStateMachineTest**

```java
package com.pk.support_ticket_api.tickets.service;

import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("TicketStateMachine 狀態機測試")
class TicketStateMachineTest {

    private final TicketStateMachine stateMachine = new TicketStateMachine();

    @ParameterizedTest
    @CsvSource({
        "OPEN, IN_PROGRESS, true",
        "OPEN, CLOSED, true",
        "OPEN, RESOLVED, false",
        "IN_PROGRESS, OPEN, true",
        "IN_PROGRESS, RESOLVED, true",
        "IN_PROGRESS, CLOSED, false",
        "RESOLVED, OPEN, true",
        "RESOLVED, CLOSED, true",
        "RESOLVED, IN_PROGRESS, false",
        "CLOSED, OPEN, false",
        "CLOSED, IN_PROGRESS, false",
        "CLOSED, RESOLVED, false"
    })
    @DisplayName("驗證狀態轉換規則")
    void canTransition_shouldValidateTransitionRules(
            TicketStatus from, TicketStatus to, boolean expected
    ) {
        assertThat(stateMachine.canTransition(from, to)).isEqualTo(expected);
    }

    @Test
    @DisplayName("CLOSED 為終態")
    void isFinalState_shouldReturnTrueForClosed() {
        assertThat(stateMachine.isFinalState(TicketStatus.CLOSED)).isTrue();
        assertThat(stateMachine.isFinalState(TicketStatus.OPEN)).isFalse();
    }

    @Test
    @DisplayName("CLOSED 沒有允許的轉換")
    void getAllowedTransitions_closedShouldHaveNoTransitions() {
        assertThat(stateMachine.getAllowedTransitions(TicketStatus.CLOSED)).isEmpty();
    }
}
```

**TicketServiceTest**

```java
package com.pk.support_ticket_api.tickets.service;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.common.exception.ForbiddenOperationException;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.dto.CreateTicketRequest;
import com.pk.support_ticket_api.tickets.dto.TicketStatusUpdateRequest;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TicketService 商業邏輯測試")
class TicketServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Spy
    private TicketStateMachine stateMachine = new TicketStateMachine();

    @Mock
    private com.pk.support_ticket_api.categories.repository.CategoryRepository categoryRepository;

    @Mock
    private com.pk.support_ticket_api.users.repository.UserRepository userRepository;

    @Mock
    private Clock clock;

    @InjectMocks
    private TicketServiceImpl ticketService;

    private UUID ticketId;
    private UUID categoryId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        ticketId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        userId = UUID.randomUUID();

        // 固定時間
        when(clock.instant()).thenReturn(Instant.now());
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
    }

    @Test
    @DisplayName("狀態從 OPEN 轉換到 IN_PROGRESS 應成功")
    void updateStatus_openToInProgress_shouldSucceed() {
        Ticket ticket = createTicket(TicketStatus.OPEN);
        when(ticketRepository.findById(ticketId)).thenReturn(java.util.Optional.of(ticket));
        when(ticketRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.IN_PROGRESS);
        ticketService.updateStatus(ticketId, request);

        verify(ticketRepository).save(argThat(t -> t.getStatus() == TicketStatus.IN_PROGRESS));
    }

    @Test
    @DisplayName("狀態從 CLOSED 轉換到 OPEN 應拋出 ForbiddenOperationException")
    void updateStatus_closedToOpen_shouldThrowException() {
        Ticket ticket = createTicket(TicketStatus.CLOSED);
        when(ticketRepository.findById(ticketId)).thenReturn(java.util.Optional.of(ticket));

        TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.OPEN);

        assertThatThrownBy(() -> ticketService.updateStatus(ticketId, request))
            .isInstanceOf(ForbiddenOperationException.class)
            .hasMessageContaining("Cannot transition");
    }

    @Test
    @DisplayName("轉換到 RESOLVED 時應設定 resolvedAt")
    void updateStatus_toResolved_shouldSetResolvedAt() {
        Ticket ticket = createTicket(TicketStatus.IN_PROGRESS);
        when(ticketRepository.findById(ticketId)).thenReturn(java.util.Optional.of(ticket));
        when(ticketRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TicketStatusUpdateRequest request = new TicketStatusUpdateRequest(TicketStatus.RESOLVED);
        ticketService.updateStatus(ticketId, request);

        verify(ticketRepository).save(argThat(t -> t.getResolvedAt() != null));
    }

    private Ticket createTicket(TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setId(ticketId);
        ticket.setTitle("Test Ticket");
        ticket.setStatus(status);
        ticket.setPriority(TicketPriority.MEDIUM);
        ticket.setCategoryId(categoryId);
        ticket.setCreatedBy(userId);
        return ticket;
    }
}
```

---

## 4. 驗收標準

| # | 標準 | 測試方式 |
|---|------|----------|
| 1 | 狀態機正確驗證所有合法轉換 | `TicketStateMachineTest` 覆蓋所有 CsvSource 案例 |
| 2 | CLOSED 為終態，無法再轉換 | `updateStatus_closedToOpen_shouldThrowException` |
| 3 | OPEN → IN_PROGRESS 成功且設定 firstResponseAt | `updateStatus_toInProgress_shouldSetFirstResponseAt` |
| 4 | IN_PROGRESS → RESOLVED 成功且設定 resolvedAt | `updateStatus_toResolved_shouldSetResolvedAt` |
| 5 | 優先級變更時重新計算 SLA | 需新增測試 |
| 6 | 所有 API 端點返回正確 HTTP Status | Controller 整合測試 |
| 7 | 樂觀鎖衝突時返回 409 | 需驗證 `OptimisticLockException` handler 已處理 |

---

## 5. 實作順序建議

```
1. [Step 1] Domain 層（Entity + StateMachine + Specification）
   ↓
2. [Step 2] Repository 層
   ↓
3. [Step 3] DTO 層（所有 Request/Response）
   ↓
4. [Step 4] Service 層（實作商業邏輯）
   ↓
5. [Step 5] Controller 層（API 端點）
   ↓
6. [Step 6] Exception + 單元測試
```

---

## 6. 預計新增檔案清單

| Step | 檔案路徑 |
|------|----------|
| DB | `src/main/resources/db/migration/V5__create_tickets_table.sql` |
| 1 | `src/main/java/com/pk/support_ticket_api/tickets/domain/Ticket.java` |
| 1 | `src/main/java/com/pk/support_ticket_api/tickets/domain/TicketSpecification.java` |
| 1 | `src/main/java/com/pk/support_ticket_api/tickets/service/TicketStateMachine.java` |
| 2 | `src/main/java/com/pk/support_ticket_api/tickets/repository/TicketRepository.java` |
| 3 | `src/main/java/com/pk/support_ticket_api/tickets/dto/CreateTicketRequest.java` |
| 3 | `src/main/java/com/pk/support_ticket_api/tickets/dto/UpdateTicketRequest.java` |
| 3 | `src/main/java/com/pk/support_ticket_api/tickets/dto/TicketStatusUpdateRequest.java` |
| 3 | `src/main/java/com/pk/support_ticket_api/tickets/dto/TicketAssignRequest.java` |
| 3 | `src/main/java/com/pk/support_ticket_api/tickets/dto/TicketResponse.java` |
| 3 | `src/main/java/com/pk/support_ticket_api/tickets/dto/TicketSummaryResponse.java` |
| 3 | `src/main/java/com/pk/support_ticket_api/tickets/dto/TicketFilterRequest.java` |
| 4 | `src/main/java/com/pk/support_ticket_api/tickets/service/TicketService.java` |
| 4 | `src/main/java/com/pk/support_ticket_api/tickets/service/TicketServiceImpl.java` |
| 5 | `src/main/java/com/pk/support_ticket_api/tickets/web/TicketController.java` |
| 5 | `src/main/java/com/pk/support_ticket_api/tickets/web/TicketAdminController.java` |
| 6 | `src/main/java/com/pk/support_ticket_api/tickets/exception/TicketException.java` |
| 6 | `src/test/java/com/pk/support_ticket_api/tickets/service/TicketStateMachineTest.java` |
| 6 | `src/test/java/com/pk/support_ticket_api/tickets/service/TicketServiceTest.java` |

---

## 7. 已知風險與待確認事項

| 風險 | 說明 | 建議 |
|------|------|------|
| 資料遷移 | 刪除重建 tickets 表格會遺失資料 | 確認是否已有資料，若有需規劃資料遷移腳本 |
| enrichResponse 實作 | 目前為佔位實作，需完整 | 使用 JOIN FETCH 或額外查詢 |
| 既有 SlaCalculator | SlaCalculator 已存在 | 重構使用 `SlaCalculator.calculateSlaDueAt(category, priority, createdAt)` |

---

## 8. 操作權限設計

### 8.1 角色權限矩陣

| 操作 | CUSTOMER | AGENT | ADMIN |
|------|:---------:|:-----:|:-----:|
| 建立 Ticket | ✅ | ❌ | ✅ |
| 讀取自己的 Ticket | ✅ | ✅ | ✅ |
| 讀取被指派的 Ticket | ❌ | ✅ | ✅ |
| 讀取所有 Ticket | ❌ | ❌ | ✅ |
| 補充 description | ✅（自己的）| ✅（被指派的）| ✅ |
| 更新 Ticket | ❌ | ❌ | ✅ |
| 變更狀態 | ❌ | ✅（被指派的）| ✅ |
| 指派 Ticket | ❌ | ❌ | ✅ |
| 刪除 Ticket | ❌ | ❌ | ✅ |

### 8.2 Ticket 建立規則

```
建立時自動設定：
- status = OPEN
- createdBy = currentUser
- slaDueAt = SlaCalculator.calculateSlaDueAt(category, priority, createdAt)
```

### 8.3 Controller 層權限設計

#### 8.3.1 TicketController（`/api/v1/tickets`）

需根據角色調整存取權限：

```java
@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
@Tag(name = "Tickets", description = "工單管理 API")
public class TicketController {

    private final TicketService ticketService;

    // === CUSTOMER & ADMIN ===

    @PostMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    public ResponseEntity<TicketResponse> create(...) { }

    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> getById(...) {
        // 權限檢查：
        // - ADMIN：可讀取所有
        // - CUSTOMER：只能讀取自己建立的
        // - AGENT：只能讀取被指派給自己的
    }

    @GetMapping
    public ResponseEntity<PageResponse<TicketSummaryResponse>> findAll(...) {
        // 權限檢查：
        // - ADMIN：可讀取所有
        // - CUSTOMER：只能讀取自己建立的（自動過濾 createdBy = currentUser）
        // - AGENT：讀取被指派給自己的（自動過濾 assignedTo = currentUser）
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TicketResponse> update(...) { }

    // === AGENT & ADMIN ===

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('AGENT', 'ADMIN')")
    public ResponseEntity<TicketResponse> updateStatus(...) {
        // 權限檢查：
        // - ADMIN：可變更所有
        // - AGENT：只能變更加給自己的
    }

    // === ADMIN ONLY ===

    @PatchMapping("/{id}/assign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TicketResponse> assign(...) { }
}
```

#### 8.3.2 TicketAdminController（`/api/v1/admin/tickets`）

```java
@RestController
@RequestMapping("/api/v1/admin/tickets")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Tickets", description = "管理者工單管理 API")
public class TicketAdminController {

    private final TicketService ticketService;

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(...) { }
}
```

### 8.4 Service 層權限驗證

#### 8.4.1 Service 介面更新

```java
public interface TicketService {

    // 建立（傳入建立者 ID）
    TicketResponse createTicket(CreateTicketRequest request, UUID createdBy);

    // 讀取（傳入 currentUser 進行權限過濾）
    TicketResponse getTicketById(UUID id, CurrentUser currentUser);
    PageResponse<TicketSummaryResponse> getTickets(
        TicketFilterRequest filter,
        Pageable pageable,
        CurrentUser currentUser  // 用於權限過濾
    );

    // 更新（僅 ADMIN）
    TicketResponse updateTicket(UUID id, UpdateTicketRequest request);

    // 狀態變更（傳入 currentUser 驗證指派）
    TicketResponse updateStatus(UUID id, TicketStatusUpdateRequest request, CurrentUser currentUser);

    // 指派（僅 ADMIN）
    TicketResponse assignTicket(UUID id, TicketAssignRequest request);

    // 刪除（僅 ADMIN）
    void deleteTicket(UUID id);
}
```

#### 8.4.2 權限驗證邏輯

```java
@Override
public TicketResponse getTicketById(UUID id, CurrentUser currentUser) {
    Ticket ticket = findTicketById(id);

    // 權限檢查
    if (!hasReadPermission(ticket, currentUser)) {
        throw new ForbiddenOperationException("No permission to view this ticket");
    }

    return enrichResponse(ticket);
}

@Override
public PageResponse<TicketSummaryResponse> getTickets(
        TicketFilterRequest filter,
        Pageable pageable,
        CurrentUser currentUser
) {
    // 根據角色調整查詢過濾條件
    TicketFilterRequest adjustedFilter = applyPermissionFilter(filter, currentUser);

    Page<Ticket> page = ticketRepository.findAll(
        TicketSpecification.withFilters(...),
        pageable
    );

    return PageResponse.from(page, TicketSummaryResponse::from);
}

@Override
public TicketResponse updateStatus(
        UUID id,
        TicketStatusUpdateRequest request,
        CurrentUser currentUser
) {
    Ticket ticket = findTicketById(id);

    // 權限檢查：ADMIN 或被指派的 AGENT
    if (!canChangeStatus(ticket, currentUser)) {
        throw new ForbiddenOperationException("No permission to change ticket status");
    }

    // 狀態機驗證
    if (!stateMachine.canTransition(ticket.getStatus(), request.status())) {
        throw new ForbiddenOperationException(String.format(
            "Cannot transition from %s to %s",
            ticket.getStatus(),
            request.status()
        ));
    }

    ticket.setStatus(request.status());
    handleStatusSideEffects(ticket, request.status());

    return enrichResponse(ticketRepository.save(ticket));
}

private boolean hasReadPermission(Ticket ticket, CurrentUser currentUser) {
    return switch (Role.valueOf(currentUser.role())) {
        case ADMIN -> true;
        case AGENT -> ticket.getAssignedTo() != null
                && ticket.getAssignedTo().equals(currentUser.userId());
        case CUSTOMER -> ticket.getCreatedBy().equals(currentUser.userId());
    };
}

private boolean canChangeStatus(Ticket ticket, CurrentUser currentUser) {
    return switch (Role.valueOf(currentUser.role())) {
        case ADMIN -> true;
        case AGENT -> ticket.getAssignedTo() != null
                && ticket.getAssignedTo().equals(currentUser.userId());
        case CUSTOMER -> false;
    };
}

private TicketFilterRequest applyPermissionFilter(
        TicketFilterRequest filter,
        CurrentUser currentUser
) {
    return switch (Role.valueOf(currentUser.role())) {
        case ADMIN -> filter; // 不限制
        case AGENT -> new TicketFilterRequest(
            filter.statuses(),
            filter.priority(),
            filter.categoryId(),
            currentUser.userId(),  // 強制過濾 assignedTo = currentUser
            null,                  // 不限制 createdBy
            filter.keyword()
        );
        case CUSTOMER -> new TicketFilterRequest(
            filter.statuses(),
            filter.priority(),
            filter.categoryId(),
            null,                  // 不限制 assignedTo
            currentUser.userId(),  // 強制過濾 createdBy = currentUser
            filter.keyword()
        );
    };
}
```

### 8.5 權限驗證流程圖

```
┌─────────────┐     ┌──────────────┐     ┌───────────────┐
│  Request    │────▶│  Controller   │────▶│  Service      │
│  (JWT)      │     │  @PreAuthorize│     │  商業邏輯     │
└─────────────┘     └──────────────┘     └───────────────┘
                          │                      │
                          │                      │
                    角色驗證                   資料範圍
                    (@PreAuthorize)          過濾（Service層）
```

### 8.6 更新後的預計新增檔案

無需新增檔案，權限邏輯融入既有程式碼。

### 8.7 更新後的驗收標準

| # | 標準 | 測試方式 |
|---|------|----------|
| 8 | Customer 無法建立 Ticket | 需驗證 `hasAnyRole('CUSTOMER', 'ADMIN')` |
| 9 | Customer 只能讀取自己的 Ticket | 過濾 createdBy |
| 10 | Agent 只能變更加給自己的 Ticket 狀態 | 驗證 assignedTo |
| 11 | Admin 可操作所有 Ticket | 驗證 hasRole('ADMIN') |

---

## 9. SLA 計算整合

### 9.1 重構 SlaCalculator 使用

```java
@Service
@RequiredArgsConstructor
@Transactional
public class TicketServiceImpl implements TicketService {

    private final TicketRepository ticketRepository;
    private final TicketStateMachine stateMachine;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final SlaCalculator slaCalculator;  // 注入既有服務
    private final Clock clock;

    @Override
    public TicketResponse createTicket(CreateTicketRequest request, UUID createdBy) {
        // 驗證 Category 存在
        Category category = categoryRepository.findById(request.categoryId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Category not found: " + request.categoryId()));

        TicketPriority priority = request.priority() != null
            ? request.priority()
            : TicketPriority.MEDIUM;

        // 使用 SlaCalculator 計算 SLA
        Instant createdAt = Instant.now(clock);
        Instant slaDueAt = slaCalculator.calculateSlaDueAt(category, priority, createdAt);

        // 建立 Entity
        Ticket ticket = new Ticket();
        ticket.setTitle(request.title());
        ticket.setDescription(request.description());
        ticket.setCategoryId(request.categoryId());
        ticket.setCreatedBy(createdBy);
        ticket.setPriority(priority);
        ticket.setSlaDeadline(slaDueAt);
        ticket.setStatus(TicketStatus.OPEN);

        Ticket saved = ticketRepository.save(ticket);
        return enrichResponse(saved);
    }
}
```

### 9.2 SlaCalculator 現有實作複用

現有 `SlaCalculator.calculateSlaDueAt(Category, TicketPriority, Instant)` 符合需求，直接注入使用。
