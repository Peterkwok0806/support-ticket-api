package com.pk.support_ticket_api.comments.service;

import com.pk.support_ticket_api.audit.domain.AuditLog;
import com.pk.support_ticket_api.audit.repository.AuditLogRepository;
import com.pk.support_ticket_api.comments.domain.Comment;
import com.pk.support_ticket_api.comments.dto.CommentResponse;
import com.pk.support_ticket_api.comments.dto.CreateCommentRequest;
import com.pk.support_ticket_api.comments.repository.CommentRepository;
import com.pk.support_ticket_api.common.domain.enums.Role;
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
    private final AuditLogRepository auditLogRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;

    @Override
    public CommentResponse createComment(
            UUID ticketId,
            CreateCommentRequest request,
            CurrentUser currentUser
    ) {
        Ticket ticket = ticketRepository.findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Ticket not found: " + ticketId));

        validateTicketAccess(ticket, currentUser);

        boolean isInternal = Boolean.TRUE.equals(request.internal());

        if (isInternal && currentUser.role() == Role.CUSTOMER) {
            throw new ForbiddenOperationException(
                "Customer cannot create internal notes");
        }

        Comment comment = new Comment();
        comment.setTicketId(ticketId);
        comment.setAuthorId(currentUser.userId());
        comment.setContent(request.content());
        comment.setInternal(isInternal);

        Comment saved = commentRepository.save(comment);

        // 記錄 Audit Log
        auditLogRepository.save(AuditLog.createCommentAdded(
            currentUser.userId(),
            ticketId,
            isInternal
        ));

        return enrichResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommentResponse> getCommentsByTicketId(
            UUID ticketId,
            CurrentUser currentUser
    ) {
        Ticket ticket = ticketRepository.findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Ticket not found: " + ticketId));

        validateTicketAccess(ticket, currentUser);

        List<Comment> comments;
        if (canViewInternal(currentUser)) {
            comments = commentRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        } else {
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
        Ticket ticket = ticketRepository.findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Ticket not found: " + ticketId));

        validateTicketAccess(ticket, currentUser);

        Comment comment = commentRepository.findById(commentId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Comment not found: " + commentId));

        if (!comment.getTicketId().equals(ticketId)) {
            throw new ResourceNotFoundException(
                "Comment not found: " + commentId);
        }

        if (comment.isInternal() && !canViewInternal(currentUser)) {
            throw new ForbiddenOperationException(
                "No permission to view this comment");
        }

        return enrichResponse(comment);
    }

    private void validateTicketAccess(Ticket ticket, CurrentUser currentUser) {
        switch (currentUser.role()) {
            case ADMIN -> {
            }
            case AGENT -> {
                if (ticket.getAssignedTo() == null
                    || !ticket.getAssignedTo().equals(currentUser.userId())) {
                    throw new ForbiddenOperationException(
                        "No permission to access this ticket");
                }
            }
            case CUSTOMER -> {
                if (!ticket.getCreatedBy().equals(currentUser.userId())) {
                    throw new ForbiddenOperationException(
                        "No permission to access this ticket");
                }
            }
        }
    }

    private boolean canViewInternal(CurrentUser currentUser) {
        return currentUser.role() == Role.ADMIN
            || currentUser.role() == Role.AGENT;
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
