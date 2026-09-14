package com.pk.support_ticket_api.audit.domain;

import com.pk.support_ticket_api.common.domain.enums.AuditAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuditAction 枚舉完整性測試")
class AuditActionTest {

    @Test
    @DisplayName("AuditAction 應包含所有預期的事件")
    void shouldContainAllExpectedActions() {
        // Given
        Set<String> expectedActions = Set.of(
            "TICKET_CREATED",
            "STATUS_CHANGED",
            "PRIORITY_CHANGED",
            "ASSIGNED",
            "UNASSIGNED",
            "COMMENT_ADDED",
            "ATTACHMENT_ADDED",
            "RESOLVED",
            "CLOSED"
        );

        // When
        Set<String> actualActions = Arrays.stream(AuditAction.values())
            .map(Enum::name)
            .collect(Collectors.toSet());

        // Then
        assertThat(actualActions)
            .containsExactlyInAnyOrderElementsOf(expectedActions);
    }

    @Test
    @DisplayName("AuditAction 不應包含 REOPENED")
    void shouldNotContainReopened() {
        // Given & When
        boolean hasReopened = Arrays.stream(AuditAction.values())
            .anyMatch(a -> a.name().equals("REOPENED"));

        // Then
        assertThat(hasReopened).isFalse();
    }

    @Test
    @DisplayName("AuditAction 枚舉值數量應為 9")
    void shouldHaveNineValues() {
        // Given & When
        int count = AuditAction.values().length;

        // Then
        assertThat(count).isEqualTo(9);
    }
}
