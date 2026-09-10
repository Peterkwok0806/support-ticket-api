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
