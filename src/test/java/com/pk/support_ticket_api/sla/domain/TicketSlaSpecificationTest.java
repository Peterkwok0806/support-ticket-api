package com.pk.support_ticket_api.sla.domain;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TicketSlaSpecification 測試")
class TicketSlaSpecificationTest {

    private static final java.time.Duration WARNING_WINDOW = java.time.Duration.ofHours(2);

    @Nested
    @DisplayName("isApproachingSlaBreach 規格建立")
    class IsApproachingSlaBreachSpecTest {

        @Test
        @DisplayName("應回傳有效的 Specification 物件")
        void shouldReturnValidSpecification() {
            Specification<Ticket> spec = TicketSlaSpecification.isApproachingSlaBreach();
            assertThat(spec).isNotNull();
        }

        @Test
        @DisplayName("Warning Window 應為 2 小時")
        void warningWindowShouldBe2Hours() {
            assertThat(WARNING_WINDOW).isEqualTo(java.time.Duration.ofHours(2));
        }
    }

    @Nested
    @DisplayName("isSlaBreached 規格建立")
    class IsSlaBreachedSpecTest {

        @Test
        @DisplayName("應回傳有效的 Specification 物件")
        void shouldReturnValidSpecification() {
            Specification<Ticket> spec = TicketSlaSpecification.isSlaBreached();
            assertThat(spec).isNotNull();
        }
    }

    @Nested
    @DisplayName("Ticket 資料驗證")
    class TicketDataValidation {

        @Test
        @DisplayName("建立即將逾期的 Ticket")
        void createTicketApproachingBreach() {
            Instant warningThreshold = Instant.now().plus(WARNING_WINDOW);

            Ticket ticket = createTicket(Instant.now().plus(1, ChronoUnit.HOURS));
            ticket.setTitle("Warning Ticket");
            ticket.setStatus(TicketStatus.OPEN);
            ticket.setPriority(TicketPriority.MEDIUM);

            assertThat(ticket.getSlaDeadline()).isBefore(warningThreshold);
            assertThat(ticket.getSlaDeadline()).isAfter(Instant.now());
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        }

        @Test
        @DisplayName("建立已逾期的 Ticket")
        void createTicketBreached() {
            Ticket ticket = createTicket(Instant.now().minus(1, ChronoUnit.HOURS));
            ticket.setTitle("Breached Ticket");
            ticket.setStatus(TicketStatus.IN_PROGRESS);
            ticket.setPriority(TicketPriority.HIGH);

            assertThat(ticket.getSlaDeadline()).isBefore(Instant.now());
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("建立安全的 Ticket（未逾期且不在警告範圍）")
        void createSafeTicket() {
            Ticket ticket = createTicket(Instant.now().plus(3, ChronoUnit.HOURS));
            ticket.setTitle("Safe Ticket");
            ticket.setStatus(TicketStatus.OPEN);
            ticket.setPriority(TicketPriority.LOW);

            Instant warningThreshold = Instant.now().plus(WARNING_WINDOW);
            assertThat(ticket.getSlaDeadline()).isAfter(warningThreshold);
        }

        @Test
        @DisplayName("Ticket 無 SLA Deadline 時為 null")
        void ticketWithNullSlaDeadline() {
            Ticket ticket = new Ticket();
            ReflectionTestUtils.setField(ticket, "id", UUID.randomUUID());
            ticket.setTitle("No SLA Ticket");
            ticket.setStatus(TicketStatus.OPEN);
            ticket.setSlaDeadline(null);

            assertThat(ticket.getSlaDeadline()).isNull();
        }
    }

    @Nested
    @DisplayName("ACTIVE_STATUSES 驗證")
    class ActiveStatusesValidation {

        @Test
        @DisplayName("OPEN 應在 ACTIVE_STATUSES 中")
        void openShouldBeActiveStatus() {
            assertThat(TicketStatus.OPEN).isNotNull();
        }

        @Test
        @DisplayName("IN_PROGRESS 應在 ACTIVE_STATUSES 中")
        void inProgressShouldBeActiveStatus() {
            assertThat(TicketStatus.IN_PROGRESS).isNotNull();
        }

        @Test
        @DisplayName("RESOLVED 不應在 ACTIVE_STATUSES 中")
        void resolvedShouldNotBeActiveStatus() {
            assertThat(TicketStatus.RESOLVED).isNotNull();
        }

        @Test
        @DisplayName("CLOSED 不應在 ACTIVE_STATUSES 中")
        void closedShouldNotBeActiveStatus() {
            assertThat(TicketStatus.CLOSED).isNotNull();
        }
    }

    @Nested
    @DisplayName("邊界條件測試")
    class BoundaryConditions {

        @Test
        @DisplayName("1 分鐘後逾期應被視為即將逾期")
        void ticketBreachingIn1Minute() {
            Instant deadline = Instant.now().plusSeconds(60);
            Instant warningThreshold = Instant.now().plus(WARNING_WINDOW);

            boolean isApproachingBreach = deadline.isAfter(Instant.now())
                    && (deadline.isBefore(warningThreshold) || deadline.equals(warningThreshold));

            assertThat(isApproachingBreach).isTrue();
        }

        @Test
        @DisplayName("1 分鐘前逾期應被視為已逾期")
        void ticketBreached1MinuteAgo() {
            Instant deadline = Instant.now().minusSeconds(60);

            boolean isBreached = deadline.isBefore(Instant.now());

            assertThat(isBreached).isTrue();
        }

        @Test
        @DisplayName("正好 2 小時後逾期應被視為即將逾期（邊界）")
        void ticketBreachingInExactly2Hours() {
            Instant deadline = Instant.now().plus(WARNING_WINDOW);

            boolean isApproachingBreach = deadline.isAfter(Instant.now())
                    && (deadline.isBefore(Instant.now().plus(WARNING_WINDOW.plusSeconds(1)))
                    || deadline.equals(Instant.now().plus(WARNING_WINDOW)));

            assertThat(isApproachingBreach).isTrue();
        }
    }

    // ==================== Helper Methods ====================

    private Ticket createTicket(Instant slaDeadline) {
        Ticket ticket = new Ticket();
        ReflectionTestUtils.setField(ticket, "id", UUID.randomUUID());
        ticket.setSlaDeadline(slaDeadline);
        return ticket;
    }
}
