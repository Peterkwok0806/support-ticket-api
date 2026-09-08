package com.pk.support_ticket_api.tickets.service;

import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("TicketStateMachine 狀態機測試")
class TicketStateMachineTest {

    private final TicketStateMachine stateMachine = new TicketStateMachine();

    @ParameterizedTest
    @CsvSource({
        "OPEN, IN_PROGRESS, true",
        "OPEN, CLOSED, true",
        "OPEN, RESOLVED, false",
        "IN_PROGRESS, OPEN, true",
        "IN_PROGRESS, RESOLVED, true",
        "IN_PROGRESS, CLOSED, false",
        "RESOLVED, OPEN, true",
        "RESOLVED, CLOSED, true",
        "RESOLVED, IN_PROGRESS, false",
        "CLOSED, OPEN, false",
        "CLOSED, IN_PROGRESS, false",
        "CLOSED, RESOLVED, false"
    })
    @DisplayName("驗證狀態轉換規則")
    void canTransition_shouldValidateTransitionRules(
            TicketStatus from, TicketStatus to, boolean expected
    ) {
        assertThat(stateMachine.canTransition(from, to)).isEqualTo(expected);
    }

    @Test
    @DisplayName("CLOSED 為終態")
    void isFinalState_shouldReturnTrueForClosed() {
        assertThat(stateMachine.isFinalState(TicketStatus.CLOSED)).isTrue();
        assertThat(stateMachine.isFinalState(TicketStatus.OPEN)).isFalse();
    }

    @Test
    @DisplayName("CLOSED 沒有允許的轉換")
    void getAllowedTransitions_closedShouldHaveNoTransitions() {
        assertThat(stateMachine.getAllowedTransitions(TicketStatus.CLOSED)).isEmpty();
    }

    @Test
    @DisplayName("OPEN 可轉換到 IN_PROGRESS 或 CLOSED")
    void getAllowedTransitions_openShouldAllowInProgressAndClosed() {
        assertThat(stateMachine.getAllowedTransitions(TicketStatus.OPEN))
            .containsExactlyInAnyOrder(TicketStatus.IN_PROGRESS, TicketStatus.CLOSED);
    }

    @Test
    @DisplayName("IN_PROGRESS 可轉換到 OPEN 或 RESOLVED")
    void getAllowedTransitions_inProgressShouldAllowOpenAndResolved() {
        assertThat(stateMachine.getAllowedTransitions(TicketStatus.IN_PROGRESS))
            .containsExactlyInAnyOrder(TicketStatus.OPEN, TicketStatus.RESOLVED);
    }

    @Test
    @DisplayName("null 狀態回傳 false")
    void canTransition_withNullStatus_shouldReturnFalse() {
        assertThat(stateMachine.canTransition(null, TicketStatus.OPEN)).isFalse();
        assertThat(stateMachine.canTransition(TicketStatus.OPEN, null)).isFalse();
    }
}
