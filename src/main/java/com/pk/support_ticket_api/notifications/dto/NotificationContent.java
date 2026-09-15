package com.pk.support_ticket_api.notifications.dto;

import com.pk.support_ticket_api.notifications.domain.NotificationType;
import com.pk.support_ticket_api.tickets.domain.Ticket;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record NotificationContent(
        String title,
        String message
) {
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

    public static NotificationContent forSlaWarning(Ticket ticket) {
        return new NotificationContent(
                "[SLA 警告] Ticket 即將逾期",
                String.format(
                        "Ticket「%s」預計於 %s 逾期，請儘早處理。",
                        ticket.getTitle(),
                        formatDeadline(ticket.getSlaDeadline())
                )
        );
    }

    public static NotificationContent forSlaBreach(Ticket ticket) {
        return new NotificationContent(
                "[SLA 逾期] Ticket 已逾期！",
                String.format(
                        "Ticket「%s」已於 %s 逾期，需要立即處理！",
                        ticket.getTitle(),
                        formatDeadline(ticket.getSlaDeadline())
                )
        );
    }

    private static String formatDeadline(java.time.Instant deadline) {
        if (deadline == null) {
            return "未知";
        }
        return FORMATTER.format(deadline);
    }
}
