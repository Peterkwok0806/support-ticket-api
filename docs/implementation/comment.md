# Comment 模組實作步驟說明書

本文件詳細說明 Comment 模組的實作步驟，按順序分為 8 個 Phase，每個 Phase 完成後建議執行測試驗證。

---

## Phase 1：資料庫 Migration

### 1.1 建立 Migration 檔案

**檔案位置**：`src/main/resources/db/migration/V6__create_comments_table.sql`

```sql
-- Comment 留言功能
-- 用於 Customer、Agent、Admin 在同一張 Ticket 上溝通
-- 透過 internal 欄位區分「客戶看得到的回覆」與「內部討論」

CREATE TABLE comments (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id       UUID            NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    author_id       UUID            NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    content         TEXT            NOT NULL,
    internal        BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE comments IS '工單留言（包含公開回覆與內部討論）';
COMMENT ON COLUMN comments.internal IS '是否為內部留言；true=內部討論（僅 Agent/Admin 可見），false=公開回覆（所有人可見）';

-- 索引設計：用於依 Ticket 查詢 Comment 列表
CREATE INDEX idx_comments_ticket_id ON comments(ticket_id);
CREATE INDEX idx_comments_ticket_internal ON comments(ticket_id, internal);
```

### 1.2 驗證 Migration

```bash
# 執行 Migration
./mvnw flyway:migrate

# 或在開發環境重置資料庫
./mvnw flyway:clean flyway:migrate
```

---

## Phase 2：Domain Layer - Comment Entity

### 2.1 建立目錄結構

```
src/main/java/com/pk/support_ticket_api/comments/
└── domain/
```

### 2.2 建立 Comment.java

**檔案位置**：`src/main/java/com/pk/support_ticket_api/comments/domain/Comment.java`

```java
package com.pk.support_ticket_api.comments.domain;

import com.pk.support_ticket_api.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "comments")
public class Comment extends BaseEntity {

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "internal", nullable = false)
    private boolean internal = false;
}
```

**設計說明**：
- 繼承 `BaseEntity`（非 `VersionedEntity`），因為 Comment 不可編輯
- 使用 `boolean` 而非 `Boolean`，避免 null 問題
- 未設定 `unique` 約束，因為同一 Ticket 可有多個 Comment

---

## Phase 3：Repository Layer

### 3.1 建立目錄結構

```
src/main/java/com/pk/support_ticket_api/comments/
└── repository/
```

### 3.2 建立 CommentRepository.java

**檔案位置**：`src/main/java/com/pk/support_ticket_api/comments/repository/CommentRepository.java`

```java
package com.pk.support_ticket_api.comments.repository;

import com.pk.support_ticket_api.comments.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID> {

    /**
     * 查詢指定 Ticket 的所有 Comment（依時間遞增排序）
     * 用於 ADMIN / AGENT，可看到全部（含 internal note）
     */
    List<Comment> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);

    /**
     * 查詢指定 Ticket 的公開 Comment（依時間遞增排序）
     * 用於 CUSTOMER，只能看到 public comment
     */
    List<Comment> findByTicketIdAndInternalFalseOrderByCreatedAtAsc(UUID ticketId);

    /**
     * 查詢指定 Ticket 的 Comment 數量
     */
    long countByTicketId(UUID ticketId);
}
```

---

## Phase 4：DTO Layer

### 4.1 建立目錄結構

```
src/main/java/com/pk/support_ticket_api/comments/
└── dto/
```

### 4.2 建立 CreateCommentRequest.java

**檔案位置**：`src/main/java/com/pk/support_ticket_api/comments/dto/CreateCommentRequest.java`

```java
package com.pk.support_ticket_api.comments.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "新增留言請求")
public record CreateCommentRequest(

    @Schema(description = "留言內容", example = "感謝您的回覆，我已確認問題已解決")
    @NotBlank(message = "留言內容為必填")
    @Size(min = 1, max = 10000, message = "留言內容長度需 1-10000 字元")
    String content,

    @Schema(description = "是否為內部留言（僅 Agent/Admin 可見）", example = "false")
    Boolean internal
) {}
```

### 4.3 建立 CommentResponse.java

**檔案位置**：`src/main/java/com/pk/support_ticket_api/comments/dto/CommentResponse.java`

```java
package com.pk.support_ticket_api.comments.dto;

import com.pk.support_ticket_api.comments.domain.Comment;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "留言回應")
public record CommentResponse(

    @Schema(description = "留言 ID")
    UUID id,

    @Schema(description = "所屬工單 ID")
    UUID ticketId,

    @Schema(description = "作者 ID")
    UUID authorId,

    @Schema(description = "作者名稱")
    String authorName,

    @Schema(description = "留言內容")
    String content,

    @Schema(description = "是否為內部留言")
    boolean internal,

    @Schema(description = "建立時間")
    Instant createdAt,

    @Schema(description = "更新時間")
    Instant updatedAt
) {

    public static CommentResponse from(Comment comment) {
        return new CommentResponse(
            comment.getId(),
            comment.getTicketId(),
            comment.getAuthorId(),
            null,  // authorName 由 Service 層填充
            comment.getContent(),
            comment.isInternal(),
            comment.getCreatedAt(),
            comment.getUpdatedAt()
        );
    }
}
```

---

## Phase 5：Service Layer

### 5.1 建立目錄結構

```
src/main/java/com/pk/support_ticket_api/comments/
└── service/
```

### 5.2 建立 CommentService.java（介面）

**檔案位置**：`src/main/java/com/pk/support_ticket_api/comments/service/CommentService.java`

```java
package com.pk.support_ticket_api.comments.service;

import com.pk.support_ticket_api.comments.dto.CommentResponse;
import com.pk.support_ticket_api.comments.dto.CreateCommentRequest;
import com.pk.support_ticket_api.common.security.CurrentUser;

import java.util.List;
import java.util.UUID;

public interface CommentService {

    /**
     * 新增 Comment
     * 依 currentUser 執行權限驗證
     */
    CommentResponse createComment(
        UUID ticketId,
        CreateCommentRequest request,
        CurrentUser currentUser
    );

    /**
     * 查詢指定 Ticket 的 Comment 列表
     * 依 currentUser 角色過濾 internal note
     */
    List<CommentResponse> getCommentsByTicketId(
        UUID ticketId,
        CurrentUser currentUser
    );

    /**
     * 查詢單一 Comment
     * 依 currentUser 角色驗證是否可存取
     */
    CommentResponse getCommentById(
        UUID ticketId,
        UUID commentId,
        CurrentUser currentUser
    );
}
```

### 5.3 建立 CommentServiceImpl.java（實作）

**檔案位置**：`src/main/java/com/pk/support_ticket_api/comments/service/CommentServiceImpl.java`

```java
package com.pk.support_ticket_api.comments.service;

import com.pk.support_ticket_api.comments.domain.Comment;
import com.pk.support_ticket_api.comments.dto.CommentResponse;
import com.pk.support_ticket_api.comments.dto.CreateCommentRequest;
import com.pk.support_ticket_api.comments.repository.CommentRepository;
import com.pk.support_ticket_api.common.exception.ForbiddenOperationException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CommentServiceImpl implements CommentService {

    private final CommentRepository commentRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;

    @Override
    public CommentResponse createComment(
            UUID ticketId,
            CreateCommentRequest request,
            CurrentUser currentUser
    ) {
        // 1. 驗證 Ticket 存在
        Ticket ticket = ticketRepository.findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Ticket not found: " + ticketId));

        // 2. 驗證 Ticket 存取權限
        validateTicketAccess(ticket, currentUser);

        // 3. 處理 internal 欄位
        boolean isInternal = Boolean.TRUE.equals(request.internal());

        // 4. Customer 不能建立 Internal Note
        if (isInternal && "CUSTOMER".equals(currentUser.role())) {
            throw new ForbiddenOperationException(
                "Customer cannot create internal notes");
        }

        // 5. 建立 Comment
        Comment comment = new Comment();
        comment.setTicketId(ticketId);
        comment.setAuthorId(currentUser.userId());
        comment.setContent(request.content());
        comment.setInternal(isInternal);

        Comment saved = commentRepository.save(comment);
        return enrichResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommentResponse> getCommentsByTicketId(
            UUID ticketId,
            CurrentUser currentUser
    ) {
        // 1. 驗證 Ticket 存在
        Ticket ticket = ticketRepository.findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Ticket not found: " + ticketId));

        // 2. 驗證 Ticket 存取權限
        validateTicketAccess(ticket, currentUser);

        // 3. 依角色查詢
        List<Comment> comments;
        if (canViewInternal(currentUser)) {
            // ADMIN / AGENT：查詢全部
            comments = commentRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        } else {
            // CUSTOMER：僅查詢 public
            comments = commentRepository.findByTicketIdAndInternalFalseOrderByCreatedAtAsc(ticketId);
        }

        return comments.stream()
            .map(this::enrichResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CommentResponse getCommentById(
            UUID ticketId,
            UUID commentId,
            CurrentUser currentUser
    ) {
        // 1. 驗證 Ticket 存在
        Ticket ticket = ticketRepository.findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Ticket not found: " + ticketId));

        // 2. 驗證 Ticket 存取權限
        validateTicketAccess(ticket, currentUser);

        // 3. 查詢 Comment
        Comment comment = commentRepository.findById(commentId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Comment not found: " + commentId));

        // 4. 驗證 Comment 屬於該 Ticket
        if (!comment.getTicketId().equals(ticketId)) {
            throw new ResourceNotFoundException(
                "Comment not found: " + commentId);
        }

        // 5. Customer 不能讀取 Internal Note
        if (comment.isInternal() && !canViewInternal(currentUser)) {
            throw new ForbiddenOperationException(
                "No permission to view this comment");
        }

        return enrichResponse(comment);
    }

    // ========== Private Helper Methods ==========

    private void validateTicketAccess(Ticket ticket, CurrentUser currentUser) {
        switch (currentUser.role()) {
            case "ADMIN" -> {
                // ADMIN 可存取所有 Ticket
            }
            case "AGENT" -> {
                // AGENT 只能存取被指派的 Ticket
                if (ticket.getAssignedTo() == null
                    || !ticket.getAssignedTo().equals(currentUser.userId())) {
                    throw new ForbiddenOperationException(
                        "No permission to access this ticket");
                }
            }
            case "CUSTOMER" -> {
                // CUSTOMER 只能存取自己建立的 Ticket
                if (!ticket.getCreatedBy().equals(currentUser.userId())) {
                    throw new ForbiddenOperationException(
                        "No permission to access this ticket");
                }
            }
            default -> throw new ForbiddenOperationException(
                "Unknown role: " + currentUser.role());
        }
    }

    private boolean canViewInternal(CurrentUser currentUser) {
        return "ADMIN".equals(currentUser.role())
            || "AGENT".equals(currentUser.role());
    }

    private CommentResponse enrichResponse(Comment comment) {
        String authorName = userRepository.findById(comment.getAuthorId())
            .map(User::getDisplayName)
            .orElse(null);

        return new CommentResponse(
            comment.getId(),
            comment.getTicketId(),
            comment.getAuthorId(),
            authorName,
            comment.getContent(),
            comment.isInternal(),
            comment.getCreatedAt(),
            comment.getUpdatedAt()
        );
    }
}
```

---

## Phase 6：Controller Layer

### 6.1 建立目錄結構

```
src/main/java/com/pk/support_ticket_api/comments/
└── web/
```

### 6.2 建立 CommentController.java

**檔案位置**：`src/main/java/com/pk/support_ticket_api/comments/web/CommentController.java`

```java
package com.pk.support_ticket_api.comments.web;

import com.pk.support_ticket_api.comments.dto.CommentResponse;
import com.pk.support_ticket_api.comments.dto.CreateCommentRequest;
import com.pk.support_ticket_api.comments.service.CommentService;
import com.pk.support_ticket_api.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/comments")
@RequiredArgsConstructor
@Tag(name = "Comments", description = "工單留言 API")
public class CommentController {

    private final CommentService commentService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "新增留言", description = "對指定工單新增留言（公開回覆或內部討論）")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "成功建立"),
        @ApiResponse(responseCode = "400", description = "請求驗證失敗"),
        @ApiResponse(responseCode = "403", description = "無權限留言或 Customer 嘗試建立內部留言"),
        @ApiResponse(responseCode = "404", description = "工單不存在")
    })
    public ResponseEntity<CommentResponse> create(
            @PathVariable UUID ticketId,
            @Valid @RequestBody CreateCommentRequest request,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        CommentResponse response = commentService.createComment(
            ticketId, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "查詢留言列表", description = "取得指定工單的所有留言（依角色過濾 internal note）")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功取得列表"),
        @ApiResponse(responseCode = "403", description = "無權限存取此工單"),
        @ApiResponse(responseCode = "404", description = "工單不存在")
    })
    public ResponseEntity<List<CommentResponse>> findAll(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        List<CommentResponse> response = commentService.getCommentsByTicketId(
            ticketId, currentUser);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{commentId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "查詢單一留言", description = "取得指定工單的單一留言")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功取得"),
        @ApiResponse(responseCode = "403", description = "無權限讀取內部留言"),
        @ApiResponse(responseCode = "404", description = "工單或留言不存在")
    })
    public ResponseEntity<CommentResponse> getById(
            @PathVariable UUID ticketId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        CommentResponse response = commentService.getCommentById(
            ticketId, commentId, currentUser);
        return ResponseEntity.ok(response);
    }
}
```

---

## Phase 7：單元測試

### 7.1 建立目錄結構

```
src/test/java/com/pk/support_ticket_api/comments/
├── service/
│   └── CommentServiceTest.java
└── web/
    └── CommentControllerTest.java
```

### 7.2 建立 CommentServiceTest.java

**檔案位置**：`src/test/java/com/pk/support_ticket_api/comments/service/CommentServiceTest.java`

```java
package com.pk.support_ticket_api.comments.service;

import com.pk.support_ticket_api.comments.domain.Comment;
import com.pk.support_ticket_api.comments.dto.CommentResponse;
import com.pk.support_ticket_api.comments.dto.CreateCommentRequest;
import com.pk.support_ticket_api.comments.repository.CommentRepository;
import com.pk.support_ticket_api.common.exception.ForbiddenOperationException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CommentService 商業邏輯測試")
class CommentServiceTest {

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CommentServiceImpl commentService;

    private UUID ticketId;
    private UUID customerId;
    private UUID agentId;
    private UUID adminId;
    private UUID commentId;
    private Ticket ticket;
    private Comment comment;
    private CurrentUser customerUser;
    private CurrentUser agentUser;
    private CurrentUser adminUser;

    @BeforeEach
    void setUp() {
        ticketId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        adminId = UUID.randomUUID();
        commentId = UUID.randomUUID();

        customerUser = new CurrentUser(customerId, "customer@test.com", "CUSTOMER");
        agentUser = new CurrentUser(agentId, "agent@test.com", "AGENT");
        adminUser = new CurrentUser(adminId, "admin@test.com", "ADMIN");

        // 建立 Ticket（由 customer 建立，assignedTo agent）
        ticket = new Ticket();
        ReflectionTestUtils.setField(ticket, "id", ticketId);
        ReflectionTestUtils.setField(ticket, "createdAt", Instant.now());
        ticket.setCreatedBy(customerId);
        ticket.setAssignedTo(agentId);

        // 建立 Comment
        comment = new Comment();
        ReflectionTestUtils.setField(comment, "id", commentId);
        ReflectionTestUtils.setField(comment, "createdAt", Instant.now());
        ReflectionTestUtils.setField(comment, "updatedAt", Instant.now());
        comment.setTicketId(ticketId);
        comment.setAuthorId(agentId);
        comment.setContent("Test comment");
        comment.setInternal(false);
    }

    // ========== Create Comment Tests ==========

    @Nested
    class CreateComment {

        @Test
        @DisplayName("Customer 可對自己的 Ticket 新增 Public Comment")
        void customerCreatePublicComment_ownTicket_success() {
            // Given
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.findById(any())).thenReturn(Optional.empty());

            CreateCommentRequest request = new CreateCommentRequest("Hello", false);

            // When
            CommentResponse response = commentService.createComment(ticketId, request, customerUser);

            // Then
            assertThat(response.content()).isEqualTo("Hello");
            assertThat(response.internal()).isFalse();
            verify(commentRepository).save(argThat(c ->
                c.getContent().equals("Hello") && !c.isInternal()
            ));
        }

        @Test
        @DisplayName("Customer 嘗試建立 Internal Note 應拋出例外")
        void customerCreateInternalNote_shouldThrowException() {
            // Given
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            CreateCommentRequest request = new CreateCommentRequest("Internal", true);

            // When / Then
            assertThatThrownBy(() -> commentService.createComment(ticketId, request, customerUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Customer cannot create internal notes");
        }

        @Test
        @DisplayName("Agent 可對被指派的 Ticket 新增 Internal Note")
        void agentCreateInternalNote_assignedTicket_success() {
            // Given
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.findById(any())).thenReturn(Optional.empty());

            CreateCommentRequest request = new CreateCommentRequest("Internal discussion", true);

            // When
            CommentResponse response = commentService.createComment(ticketId, request, agentUser);

            // Then
            assertThat(response.content()).isEqualTo("Internal discussion");
            assertThat(response.internal()).isTrue();
        }

        @Test
        @DisplayName("Agent 嘗試對未指派的 Ticket 留言應拋出例外")
        void agentCreateComment_unassignedTicket_shouldThrowException() {
            // Given
            ticket.setAssignedTo(UUID.randomUUID()); // 指派給其他 Agent
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            CreateCommentRequest request = new CreateCommentRequest("Hello", false);

            // When / Then
            assertThatThrownBy(() -> commentService.createComment(ticketId, request, agentUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("No permission to access this ticket");
        }

        @Test
        @DisplayName("Admin 可對任何 Ticket 新增 Comment")
        void adminCreateComment_anyTicket_success() {
            // Given
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.findById(any())).thenReturn(Optional.empty());

            CreateCommentRequest request = new CreateCommentRequest("Admin comment", true);

            // When
            CommentResponse response = commentService.createComment(ticketId, request, adminUser);

            // Then
            assertThat(response.content()).isEqualTo("Admin comment");
            assertThat(response.internal()).isTrue();
        }
    }

    // ========== Get Comments Tests ==========

    @Nested
    class GetComments {

        @Test
        @DisplayName("Customer 查詢時只能看到 Public Comment")
        void customerGetComments_onlyPublic() {
            // Given
            Comment internalComment = new Comment();
            ReflectionTestUtils.setField(internalComment, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(internalComment, "createdAt", Instant.now());
            ReflectionTestUtils.setField(internalComment, "updatedAt", Instant.now());
            internalComment.setTicketId(ticketId);
            internalComment.setAuthorId(agentId);
            internalComment.setContent("Internal note");
            internalComment.setInternal(true);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.findByTicketIdAndInternalFalseOrderByCreatedAtAsc(ticketId))
                .thenReturn(List.of(comment));

            // When
            List<CommentResponse> responses = commentService.getCommentsByTicketId(ticketId, customerUser);

            // Then
            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).content()).isEqualTo("Test comment");
        }

        @Test
        @DisplayName("Agent 查詢時可看到全部 Comment（含 Internal）")
        void agentGetComments_all() {
            // Given
            Comment internalComment = new Comment();
            ReflectionTestUtils.setField(internalComment, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(internalComment, "createdAt", Instant.now());
            ReflectionTestUtils.setField(internalComment, "updatedAt", Instant.now());
            internalComment.setTicketId(ticketId);
            internalComment.setAuthorId(agentId);
            internalComment.setContent("Internal note");
            internalComment.setInternal(true);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.findByTicketIdOrderByCreatedAtAsc(ticketId))
                .thenReturn(List.of(comment, internalComment));

            // When
            List<CommentResponse> responses = commentService.getCommentsByTicketId(ticketId, agentUser);

            // Then
            assertThat(responses).hasSize(2);
        }

        @Test
        @DisplayName("Admin 查詢時可看到全部 Comment（含 Internal）")
        void adminGetComments_all() {
            // Given
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.findByTicketIdOrderByCreatedAtAsc(ticketId))
                .thenReturn(List.of(comment));

            // When
            List<CommentResponse> responses = commentService.getCommentsByTicketId(ticketId, adminUser);

            // Then
            assertThat(responses).hasSize(1);
            verify(commentRepository).findByTicketIdOrderByCreatedAtAsc(ticketId);
        }
    }

    // ========== Get Comment By ID Tests ==========

    @Nested
    class GetCommentById {

        @Test
        @DisplayName("Customer 嘗試讀取 Internal Note 應拋出例外")
        void customerGetInternalNote_shouldThrowException() {
            // Given
            comment.setInternal(true);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));

            // When / Then
            assertThatThrownBy(() -> commentService.getCommentById(ticketId, commentId, customerUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("No permission to view this comment");
        }

        @Test
        @DisplayName("Agent 可讀取 Internal Note")
        void agentGetInternalNote_success() {
            // Given
            comment.setInternal(true);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
            when(userRepository.findById(any())).thenReturn(Optional.empty());

            // When
            CommentResponse response = commentService.getCommentById(ticketId, commentId, agentUser);

            // Then
            assertThat(response.internal()).isTrue();
        }
    }

    // ========== Helper Methods ==========

    // 測試中使用的 Ticket Domain
    // 由於 CommentService 只依賴 TicketRepository.findById()，我們需要一個簡單的 Ticket
    // 這裡使用 org.springframework.data.jpa.domain.AbstractPersistable 的簡化版本
    // 或者建立一個測試用的 Ticket Stub

    /**
     * 備註：實際測試時需要建立一個與 src/main/java/com/pk/support_ticket_api/tickets/domain/Ticket.java
     * 完全相同結構的測試用 Ticket 類別，或使用 @Mock 建立 Ticket mock。
     *
     * 建議：在測試中直接 mock Ticket 物件的行為，而不是建立真實物件。
     */
}
```

**注意**：由於測試程式碼較長，上方的測試範例需要根據實際情況調整 Ticket mock 方式。建議參考現有的 `TicketServiceTest.java` 中的做法。

### 7.3 建立 CommentControllerTest.java（可選）

如需 API 層級測試，可參考現有 Controller 測試模式建立。

---

## Phase 8：驗證與整合

### 8.1 執行編譯

```bash
# 編譯專案
./mvnw clean compile
```

### 8.2 執行測試

```bash
# 執行所有測試
./mvnw test

# 只執行 Comment 相關測試
./mvnw test -Dtest=CommentServiceTest
```

### 8.3 檢查 Swagger 文件

```bash
# 啟動專案
./mvnw spring-boot:run

# 開啟瀏覽器
# http://localhost:8080/swagger-ui.html
```

確認 Comment API 文件已正確產出。

### 8.4 Git Commit 建議

```bash
# 階段性提交（建議）
git add .
git commit -m "feat(comments): add Comment/Internal Note feature

- Add Comment entity with internal flag
- Add CommentService with permission validation
- Add CommentController with CRUD endpoints
- Add CommentServiceTest with permission tests

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## 實作檢查清單

### Phase 1：資料庫 Migration
- [ ] 建立 `V6__create_comments_table.sql`
- [ ] 執行 Migration 驗證

### Phase 2：Domain Layer
- [ ] 建立 `Comment.java`
- [ ] 確認繼承 `BaseEntity`

### Phase 3：Repository Layer
- [ ] 建立 `CommentRepository.java`
- [ ] 確認查詢方法正確

### Phase 4：DTO Layer
- [ ] 建立 `CreateCommentRequest.java`
- [ ] 建立 `CommentResponse.java`
- [ ] 確認 JSR-380 驗證註解

### Phase 5：Service Layer
- [ ] 建立 `CommentService.java`
- [ ] 建立 `CommentServiceImpl.java`
- [ ] 確認權限驗證邏輯正確

### Phase 6：Controller Layer
- [ ] 建立 `CommentController.java`
- [ ] 確認 API 路徑正確
- [ ] 確認 Swagger 文件正確

### Phase 7：單元測試
- [ ] 建立 `CommentServiceTest.java`
- [ ] 執行測試通過

### Phase 8：驗證與整合
- [ ] 編譯成功
- [ ] 所有測試通過
- [ ] Swagger 文件正確

---

## 常見問題與解決方案

### Q1：Customer 無法建立 Internal Note，但 internal 欄位為 null
**原因**：`Boolean` 為物件，`null` 不等於 `Boolean.TRUE`
**解決**：使用 `Boolean.TRUE.equals(request.internal())` 而非 `request.internal() == true`

### Q2：Comment 查詢效能問題
**原因**：未建立索引
**解決**：確認 Migration 中有 `idx_comments_ticket_internal` 索引

### Q3：Controller 的 `@PreAuthorize` 如何設計
**原因**：需要驗證已登入，但詳細權限由 Service 層處理
**解決**：使用 `@PreAuthorize("isAuthenticated()")`，Service 層執行 Ticket 存取權限驗證

### Q4：如何處理 Ticket 不存在的情況
**原因**：依賴 TicketRepository
**解決**：Service 層先查詢 Ticket，若不存在則拋出 `ResourceNotFoundException`
