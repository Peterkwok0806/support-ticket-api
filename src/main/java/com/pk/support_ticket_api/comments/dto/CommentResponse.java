package com.pk.support_ticket_api.comments.dto;

import com.pk.support_ticket_api.comments.domain.Comment;
import io.swagger.v3.oas.annotations.media.Schema;

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
