package com.pk.support_ticket_api.categories.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
    @NotBlank(message = "名稱為必填")
    @Size(min = 1, max = 100, message = "名稱長度需 1-100 字元")
    String name,

    @Size(max = 500, message = "描述長度最大 500 字元")
    String description,

    @NotNull(message = "SLA Low 小時數為必填")
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursLow,

    @NotNull(message = "SLA Medium 小時數為必填")
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursMedium,

    @NotNull(message = "SLA High 小時數為必填")
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursHigh,

    @NotNull(message = "SLA Urgent 小時數為必填")
    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursUrgent
) {}
