package com.pk.support_ticket_api.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Map;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI customerSupportTicketOpenApi() {
        Components components = new Components();

        // Security Scheme
        components.addSecuritySchemes(SECURITY_SCHEME_NAME,
                new SecurityScheme()
                        .name(SECURITY_SCHEME_NAME)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("輸入 JWT Token（例如：eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...）"));

        // Error Response Schema - 順序重要：先註冊被引用的 schema
        components.addSchemas("FieldErrorResponse", createFieldErrorResponseSchema());
        components.addSchemas("ErrorResponse", createErrorResponseSchema());

        // Common Error Responses - 每種錯誤類型有不同的 example
        components.addResponses("NotFound", createErrorResponse(
                "資源不存在",
                404,
                "RESOURCE_NOT_FOUND",
                "Resource not found"
        ));
        components.addResponses("BadRequest", createErrorResponse(
                "請求驗證失敗",
                400,
                "VALIDATION_ERROR",
                "Validation failed"
        ));
        components.addResponses("Conflict", createErrorResponse(
                "資源衝突",
                409,
                "RESOURCE_CONFLICT",
                "Resource already exists"
        ));
        components.addResponses("Forbidden", createErrorResponse(
                "無權限操作",
                403,
                "ACCESS_DENIED",
                "Access denied"
        ));
        components.addResponses("Unauthorized", createErrorResponse(
                "未經授權",
                401,
                "UNAUTHORIZED",
                "Authentication required"
        ));
        components.addResponses("TooManyRequests", createErrorResponse(
                "請求頻率過高",
                429,
                "RATE_LIMIT_EXCEEDED",
                "Too many requests"
        ));

        return new OpenAPI()
                .info(new Info()
                        .title("Customer Support Ticket API")
                        .version("v1")
                        .description("Customer Support Ticket System API"))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(components);
    }

    private Schema<?> createErrorResponseSchema() {
        Schema<?> errorResponse = new Schema<>();
        errorResponse.setType("object");
        errorResponse.setDescription("錯誤回應結構");

        Schema<?> timestampSchema = new Schema<>();
        timestampSchema.setType("string");
        timestampSchema.setFormat("date-time");
        timestampSchema.setDescription("錯誤發生時間");

        Schema<?> statusSchema = new Schema<>();
        statusSchema.setType("integer");
        statusSchema.setDescription("HTTP 狀態碼");

        Schema<?> codeSchema = new Schema<>();
        codeSchema.setType("string");
        codeSchema.setDescription("錯誤碼");

        Schema<?> messageSchema = new Schema<>();
        messageSchema.setType("string");
        messageSchema.setDescription("錯誤訊息");

        Schema<?> pathSchema = new Schema<>();
        pathSchema.setType("string");
        pathSchema.setDescription("請求路徑");

        Schema<?> fieldErrorsSchema = new Schema<>();
        fieldErrorsSchema.setType("array");
        fieldErrorsSchema.setDescription("欄位驗證錯誤");

        Schema<?> traceIdSchema = new Schema<>();
        traceIdSchema.setType("string");
        traceIdSchema.setDescription("追蹤 ID");

        errorResponse.setProperties(Map.of(
                "timestamp", timestampSchema,
                "status", statusSchema,
                "code", codeSchema,
                "message", messageSchema,
                "path", pathSchema,
                "fieldErrors", fieldErrorsSchema,
                "traceId", traceIdSchema
        ));

        return errorResponse;
    }

    private Schema<?> createFieldErrorResponseSchema() {
        Schema<?> fieldError = new Schema<>();
        fieldError.setType("object");
        fieldError.setDescription("欄位驗證錯誤結構");

        Schema<?> fieldSchema = new Schema<>();
        fieldSchema.setType("string");
        fieldSchema.setDescription("欄位名稱");

        Schema<?> msgSchema = new Schema<>();
        msgSchema.setType("string");
        msgSchema.setDescription("錯誤訊息");

        fieldError.setProperties(Map.of(
                "field", fieldSchema,
                "message", msgSchema
        ));

        return fieldError;
    }

    private ApiResponse createErrorResponse(String description, int status, String code, String message) {
        Map<String, Object> example = Map.of(
                "timestamp", Instant.now().toString(),
                "status", status,
                "code", code,
                "message", message,
                "path", "/v1/example/path",
                "traceId", "abc-123",
                "fieldErrors", java.util.List.of(Map.of("field", "example", "message", "error"))
        );

        Schema<?> schema = new Schema<>();
        schema.set$ref("#/components/schemas/ErrorResponse");
        schema.setExample(example);

        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(
                        "application/json",
                        new MediaType().schema(schema)));
    }
}
