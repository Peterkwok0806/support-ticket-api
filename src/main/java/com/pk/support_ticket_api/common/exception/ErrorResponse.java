package com.pk.support_ticket_api.common.exception;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "錯誤回應結構")
public record ErrorResponse(
        @Schema(description = "錯誤發生時間") Instant timestamp,
        @Schema(description = "HTTP 狀態碼") int status,
        @Schema(description = "錯誤碼") String code,
        @Schema(description = "錯誤訊息") String message,
        @Schema(description = "請求路徑") String path,
        @Schema(description = "欄位驗證錯誤") List<FieldErrorResponse> fieldErrors,
        @Schema(description = "追蹤 ID") String traceId
) {
}
