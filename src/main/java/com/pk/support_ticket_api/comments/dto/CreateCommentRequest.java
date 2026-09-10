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
