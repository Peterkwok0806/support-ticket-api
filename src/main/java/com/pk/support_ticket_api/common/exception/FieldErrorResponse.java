package com.pk.support_ticket_api.common.exception;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "欄位驗證錯誤結構")
public record FieldErrorResponse(
        @Schema(description = "欄位名稱") String field,
        @Schema(description = "錯誤訊息") String message
) {
}
