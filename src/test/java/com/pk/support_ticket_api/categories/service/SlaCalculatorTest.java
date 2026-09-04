package com.pk.support_ticket_api.categories.service;

import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SlaCalculator 測試")
class SlaCalculatorTest {

    private SlaCalculator slaCalculator;
    private Category testCategory;
    private Instant createdAt;

    @BeforeEach
    void setUp() {
        slaCalculator = new SlaCalculator();

        testCategory = new Category();
        ReflectionTestUtils.setField(testCategory, "id", UUID.randomUUID());
        testCategory.setName("技術問題");
        testCategory.setSlaHoursLow(72);
        testCategory.setSlaHoursMedium(48);
        testCategory.setSlaHoursHigh(24);
        testCategory.setSlaHoursUrgent(4);

        // 固定測試時間：2026-09-04 10:00:00 UTC
        createdAt = Instant.parse("2026-09-04T10:00:00Z");
    }

    @Nested
    @DisplayName("calculateSlaDueAt 基本計算")
    class CalculateSlaDueAtBasic {

        @Test
        @DisplayName("LOW Priority 正確計算為 72 小時後")
        void calculateSlaDueAt_LowPriority_72Hours() {
            Instant result = slaCalculator.calculateSlaDueAt(
                    testCategory,
                    TicketPriority.LOW,
                    createdAt
            );

            // 2026-09-04 10:00:00 + 72 小時 = 2026-09-07 10:00:00
            assertEquals(Instant.parse("2026-09-07T10:00:00Z"), result);
        }

        @Test
        @DisplayName("MEDIUM Priority 正確計算為 48 小時後")
        void calculateSlaDueAt_MediumPriority_48Hours() {
            Instant result = slaCalculator.calculateSlaDueAt(
                    testCategory,
                    TicketPriority.MEDIUM,
                    createdAt
            );

            // 2026-09-04 10:00:00 + 48 小時 = 2026-09-06 10:00:00
            assertEquals(Instant.parse("2026-09-06T10:00:00Z"), result);
        }

        @Test
        @DisplayName("HIGH Priority 正確計算為 24 小時後")
        void calculateSlaDueAt_HighPriority_24Hours() {
            Instant result = slaCalculator.calculateSlaDueAt(
                    testCategory,
                    TicketPriority.HIGH,
                    createdAt
            );

            // 2026-09-04 10:00:00 + 24 小時 = 2026-09-05 10:00:00
            assertEquals(Instant.parse("2026-09-05T10:00:00Z"), result);
        }

        @Test
        @DisplayName("URGENT Priority 正確計算為 4 小時後")
        void calculateSlaDueAt_UrgentPriority_4Hours() {
            Instant result = slaCalculator.calculateSlaDueAt(
                    testCategory,
                    TicketPriority.URGENT,
                    createdAt
            );

            // 2026-09-04 10:00:00 + 4 小時 = 2026-09-04 14:00:00
            assertEquals(Instant.parse("2026-09-04T14:00:00Z"), result);
        }
    }

    @Nested
    @DisplayName("calculateSlaDueAt 所有 Priority 枚舉")
    class CalculateSlaDueAtAllPriorities {

        @ParameterizedTest
        @EnumSource(TicketPriority.class)
        @DisplayName("根據 Priority 正確取得對應 SLA 小時數")
        void calculateSlaDueAt_CorrectHours(TicketPriority priority) {
            int expectedHours = switch (priority) {
                case LOW -> 72;
                case MEDIUM -> 48;
                case HIGH -> 24;
                case URGENT -> 4;
            };

            Instant result = slaCalculator.calculateSlaDueAt(testCategory, priority, createdAt);
            Instant expectedDueAt = createdAt.plus(expectedHours, ChronoUnit.HOURS);

            assertEquals(expectedDueAt, result);
        }
    }

    @Nested
    @DisplayName("不同 Category SLA 設定")
    class DifferentCategorySla {

        @Test
        @DisplayName("使用不同 Category 的 SLA 設定")
        void calculateSlaDueAt_DifferentCategorySla() {
            Category customCategory = new Category();
            ReflectionTestUtils.setField(customCategory, "id", UUID.randomUUID());
            customCategory.setName("客製化分類");
            customCategory.setSlaHoursLow(168);    // 7 天
            customCategory.setSlaHoursMedium(120); // 5 天
            customCategory.setSlaHoursHigh(72);     // 3 天
            customCategory.setSlaHoursUrgent(12);   // 12 小時

            Instant resultLow = slaCalculator.calculateSlaDueAt(
                    customCategory, TicketPriority.LOW, createdAt);
            Instant resultMedium = slaCalculator.calculateSlaDueAt(
                    customCategory, TicketPriority.MEDIUM, createdAt);
            Instant resultHigh = slaCalculator.calculateSlaDueAt(
                    customCategory, TicketPriority.HIGH, createdAt);
            Instant resultUrgent = slaCalculator.calculateSlaDueAt(
                    customCategory, TicketPriority.URGENT, createdAt);

            assertEquals(createdAt.plus(168, ChronoUnit.HOURS), resultLow);
            assertEquals(createdAt.plus(120, ChronoUnit.HOURS), resultMedium);
            assertEquals(createdAt.plus(72, ChronoUnit.HOURS), resultHigh);
            assertEquals(createdAt.plus(12, ChronoUnit.HOURS), resultUrgent);
        }
    }

    @Nested
    @DisplayName("邊界條件")
    class EdgeCases {

        @Test
        @DisplayName("最小 SLA 小時數（1 小時）")
        void calculateSlaDueAt_MinimumSlaHours() {
            Category minCategory = new Category();
            ReflectionTestUtils.setField(minCategory, "id", UUID.randomUUID());
            minCategory.setSlaHoursLow(1);
            minCategory.setSlaHoursMedium(1);
            minCategory.setSlaHoursHigh(1);
            minCategory.setSlaHoursUrgent(1);

            Instant result = slaCalculator.calculateSlaDueAt(
                    minCategory, TicketPriority.LOW, createdAt);

            assertEquals(createdAt.plus(1, ChronoUnit.HOURS), result);
        }

        @Test
        @DisplayName("最大 SLA 小時數（720 小時 = 30 天）")
        void calculateSlaDueAt_MaximumSlaHours() {
            Category maxCategory = new Category();
            ReflectionTestUtils.setField(maxCategory, "id", UUID.randomUUID());
            maxCategory.setSlaHoursLow(720);
            maxCategory.setSlaHoursMedium(720);
            maxCategory.setSlaHoursHigh(720);
            maxCategory.setSlaHoursUrgent(720);

            Instant result = slaCalculator.calculateSlaDueAt(
                    maxCategory, TicketPriority.LOW, createdAt);

            assertEquals(createdAt.plus(720, ChronoUnit.HOURS), result);
        }

        @Test
        @DisplayName("使用不同建立時間")
        void calculateSlaDueAt_DifferentCreatedAt() {
            Instant customCreatedAt = Instant.parse("2026-09-01T08:30:00Z");

            Instant result = slaCalculator.calculateSlaDueAt(
                    testCategory, TicketPriority.MEDIUM, customCreatedAt);

            assertEquals(Instant.parse("2026-09-03T08:30:00Z"), result);
        }
    }
}
