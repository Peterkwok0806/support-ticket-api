package com.pk.support_ticket_api.comments.service;

import com.pk.support_ticket_api.audit.repository.AuditLogRepository;
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
    private AuditLogRepository auditLogRepository;

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
    private User agentUserEntity;

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

        ticket = createTicket(customerId, agentId);

        comment = createComment(ticketId, agentId, false);

        agentUserEntity = new User();
        ReflectionTestUtils.setField(agentUserEntity, "id", agentId);
        agentUserEntity.setEmail("agent@test.com");
        agentUserEntity.setDisplayName("測試客服");
    }

    private Ticket createTicket(UUID createdBy, UUID assignedTo) {
        Ticket ticket = new Ticket();
        ReflectionTestUtils.setField(ticket, "id", ticketId);
        ReflectionTestUtils.setField(ticket, "createdAt", Instant.now());
        ticket.setCreatedBy(createdBy);
        ticket.setAssignedTo(assignedTo);
        return ticket;
    }

    private Comment createComment(UUID ticketId, UUID authorId, boolean internal) {
        Comment comment = new Comment();
        ReflectionTestUtils.setField(comment, "id", commentId);
        ReflectionTestUtils.setField(comment, "createdAt", Instant.now());
        ReflectionTestUtils.setField(comment, "updatedAt", Instant.now());
        comment.setTicketId(ticketId);
        comment.setAuthorId(authorId);
        comment.setContent("Test comment");
        comment.setInternal(internal);
        return comment;
    }

    @Nested
    class CreateComment {

        @Test
        @DisplayName("Customer 可對自己的 Ticket 新增 Public Comment")
        void customerCreatePublicComment_ownTicket_success() {
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.findById(any())).thenReturn(Optional.empty());

            CreateCommentRequest request = new CreateCommentRequest("Hello", false);

            CommentResponse response = commentService.createComment(ticketId, request, customerUser);

            assertThat(response.content()).isEqualTo("Hello");
            assertThat(response.internal()).isFalse();
            verify(commentRepository).save(argThat(c ->
                "Hello".equals(c.getContent()) && !c.isInternal()
            ));
        }

        @Test
        @DisplayName("Customer 嘗試建立 Internal Note 應拋出例外")
        void customerCreateInternalNote_shouldThrowException() {
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

            CreateCommentRequest request = new CreateCommentRequest("Internal", true);

            assertThatThrownBy(() -> commentService.createComment(ticketId, request, customerUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Customer cannot create internal notes");
        }

        @Test
        @DisplayName("Agent 可對被指派的 Ticket 新增 Internal Note")
        void agentCreateInternalNote_assignedTicket_success() {
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.findById(any())).thenReturn(Optional.empty());

            CreateCommentRequest request = new CreateCommentRequest("Internal discussion", true);

            CommentResponse response = commentService.createComment(ticketId, request, agentUser);

            assertThat(response.content()).isEqualTo("Internal discussion");
            assertThat(response.internal()).isTrue();
        }

        @Test
        @DisplayName("Agent 嘗試對未指派的 Ticket 留言應拋出例外")
        void agentCreateComment_unassignedTicket_shouldThrowException() {
            Ticket unassignedTicket = createTicket(customerId, UUID.randomUUID());
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(unassignedTicket));

            CreateCommentRequest request = new CreateCommentRequest("Hello", false);

            assertThatThrownBy(() -> commentService.createComment(ticketId, request, agentUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("No permission to access this ticket");
        }

        @Test
        @DisplayName("Admin 可對任何 Ticket 新增 Comment")
        void adminCreateComment_anyTicket_success() {
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.findById(any())).thenReturn(Optional.empty());

            CreateCommentRequest request = new CreateCommentRequest("Admin comment", true);

            CommentResponse response = commentService.createComment(ticketId, request, adminUser);

            assertThat(response.content()).isEqualTo("Admin comment");
            assertThat(response.internal()).isTrue();
        }

        @Test
        @DisplayName("查詢不存在的 Ticket 應拋出例外")
        void createComment_ticketNotFound_shouldThrowException() {
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());

            CreateCommentRequest request = new CreateCommentRequest("Hello", false);

            assertThatThrownBy(() -> commentService.createComment(ticketId, request, customerUser))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket not found");
        }
    }

    @Nested
    class GetCommentsByTicketId {

        @Test
        @DisplayName("Customer 查詢時只能看到 Public Comment")
        void customerGetComments_onlyPublic() {
            Comment internalComment = createComment(ticketId, agentId, true);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.findByTicketIdAndInternalFalseOrderByCreatedAtAsc(ticketId))
                .thenReturn(List.of(comment));

            List<CommentResponse> responses = commentService.getCommentsByTicketId(ticketId, customerUser);

            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).content()).isEqualTo("Test comment");
        }

        @Test
        @DisplayName("Agent 查詢時可看到全部 Comment（含 Internal）")
        void agentGetComments_all() {
            Comment internalComment = createComment(ticketId, agentId, true);

            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.findByTicketIdOrderByCreatedAtAsc(ticketId))
                .thenReturn(List.of(comment, internalComment));

            List<CommentResponse> responses = commentService.getCommentsByTicketId(ticketId, agentUser);

            assertThat(responses).hasSize(2);
        }

        @Test
        @DisplayName("Admin 查詢時可看到全部 Comment（含 Internal）")
        void adminGetComments_all() {
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.findByTicketIdOrderByCreatedAtAsc(ticketId))
                .thenReturn(List.of(comment));

            List<CommentResponse> responses = commentService.getCommentsByTicketId(ticketId, adminUser);

            assertThat(responses).hasSize(1);
            verify(commentRepository).findByTicketIdOrderByCreatedAtAsc(ticketId);
        }

        @Test
        @DisplayName("Agent 無法查詢未指派的 Ticket")
        void agentGetComments_unassignedTicket_shouldThrowException() {
            Ticket unassignedTicket = createTicket(customerId, UUID.randomUUID());
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(unassignedTicket));

            assertThatThrownBy(() -> commentService.getCommentsByTicketId(ticketId, agentUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("No permission to access this ticket");
        }
    }

    @Nested
    class GetCommentById {

        @Test
        @DisplayName("Customer 嘗試讀取 Internal Note 應拋出例外")
        void customerGetInternalNote_shouldThrowException() {
            comment.setInternal(true);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));

            assertThatThrownBy(() -> commentService.getCommentById(ticketId, commentId, customerUser))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("No permission to view this comment");
        }

        @Test
        @DisplayName("Agent 可讀取 Internal Note")
        void agentGetInternalNote_success() {
            comment.setInternal(true);
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
            when(userRepository.findById(any())).thenReturn(Optional.empty());

            CommentResponse response = commentService.getCommentById(ticketId, commentId, agentUser);

            assertThat(response.internal()).isTrue();
        }

        @Test
        @DisplayName("查詢不存在的 Comment 應拋出例外")
        void getCommentById_notFound_shouldThrowException() {
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.findById(commentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> commentService.getCommentById(ticketId, commentId, agentUser))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Comment not found");
        }

        @Test
        @DisplayName("Comment 不屬於該 Ticket 應拋出例外")
        void getCommentById_wrongTicket_shouldThrowException() {
            UUID differentTicketId = UUID.randomUUID();
            when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
            when(commentRepository.findById(commentId)).thenReturn(Optional.of(
                createComment(differentTicketId, agentId, false)));

            assertThatThrownBy(() -> commentService.getCommentById(ticketId, commentId, agentUser))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Comment not found");
        }
    }
}
