package com.pk.support_ticket_api.categories.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateCategoryRequest(
    @Size(min = 1, max = 100, message = "名稱長度需 1-100 字元")
    String name,

    @Size(max = 500, message = "描述長度最大 500 字元")
    String description,

    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursLow,

    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursMedium,

    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursHigh,

    @Min(value = 1, message = "SLA 小時數最小為 1")
    @Max(value = 720, message = "SLA 小時數最大為 720（30天）")
    Integer slaHoursUrgent
) {}
