package com.pk.support_ticket_api.tickets.service;

import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Ticket 狀態機。
 * 定義所有合法的狀態轉換規則。
 */
@Component
public class TicketStateMachine {

    private static final Map<TicketStatus, Set<TicketStatus>> TRANSITIONS;

    static {
        TRANSITIONS = new EnumMap<>(TicketStatus.class);
        TRANSITIONS.put(TicketStatus.OPEN, EnumSet.of(
            TicketStatus.IN_PROGRESS,
            TicketStatus.CLOSED
        ));
        TRANSITIONS.put(TicketStatus.IN_PROGRESS, EnumSet.of(
            TicketStatus.OPEN,
            TicketStatus.RESOLVED
        ));
        TRANSITIONS.put(TicketStatus.RESOLVED, EnumSet.of(
            TicketStatus.OPEN,
            TicketStatus.CLOSED
        ));
        TRANSITIONS.put(TicketStatus.CLOSED, EnumSet.noneOf(TicketStatus.class));
    }

    /**
     * 驗證狀態轉換是否合法。
     *
     * @param from 當前狀態
     * @param to   目標狀態
     * @return true if 允許轉換
     */
    public boolean canTransition(TicketStatus from, TicketStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<TicketStatus> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * 取得指定狀態允許的所有轉換目標。
     *
     * @param from 當前狀態
     * @return 允許的目標狀態集合
     */
    public Set<TicketStatus> getAllowedTransitions(TicketStatus from) {
        if (from == null) {
            return EnumSet.noneOf(TicketStatus.class);
        }
        return TRANSITIONS.getOrDefault(from, EnumSet.noneOf(TicketStatus.class));
    }

    /**
     * 檢查是否為終態。
     *
     * @param status 狀態
     * @return true if 為終態（CLOSED）
     */
    public boolean isFinalState(TicketStatus status) {
        return status == TicketStatus.CLOSED;
    }
}
