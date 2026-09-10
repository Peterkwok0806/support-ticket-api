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
