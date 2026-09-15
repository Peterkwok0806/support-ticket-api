package com.pk.support_ticket_api.sla.service;

import com.pk.support_ticket_api.notifications.domain.NotificationType;
import com.pk.support_ticket_api.notifications.service.NotificationService;
import com.pk.support_ticket_api.sla.domain.TicketSlaSpecification;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class SlaMonitoringService {

    private final TicketRepository ticketRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final EmailNotificationService emailNotificationService;

    /**
     * 處理 SLA Breach（已逾期）
     */
    public void processSlaBreaches() {
        List<Ticket> breachedTickets = ticketRepository.findAll(
                TicketSlaSpecification.isSlaBreached()
        );

        log.info("Found {} tickets with SLA breach", breachedTickets.size());

        for (Ticket ticket : breachedTickets) {
            try {
                sendBreachNotifications(ticket);
            } catch (DataIntegrityViolationException e) {
                // Unique Index 衝突，代表已發送過，跳過
                log.debug("SLA breach notification already sent for ticket: {}", ticket.getId());
            } catch (Exception e) {
                log.error("Failed to process SLA breach for ticket: {}", ticket.getId(), e);
            }
        }
    }

    /**
     * 處理 SLA Warning（即將逾期）
     */
    public void processSlaWarnings() {
        List<Ticket> warningTickets = ticketRepository.findAll(
                TicketSlaSpecification.isApproachingSlaBreach()
        );

        log.info("Found {} tickets approaching SLA breach", warningTickets.size());

        for (Ticket ticket : warningTickets) {
            try {
                sendWarningNotifications(ticket);
            } catch (DataIntegrityViolationException e) {
                // Unique Index 衝突，代表已發送過，跳過
                log.debug("SLA warning notification already sent for ticket: {}", ticket.getId());
            } catch (Exception e) {
                log.error("Failed to process SLA warning for ticket: {}", ticket.getId(), e);
            }
        }
    }

    // ==================== Private Methods ====================

    private void sendBreachNotifications(Ticket ticket) {
        // 通知負責 Agent
        if (ticket.getAssignedTo() != null) {
            notificationService.sendSlaNotification(
                    ticket, NotificationType.SLA_BREACH, ticket.getAssignedTo()
            );
        }

        // 通知所有 Admin
        List<UUID> adminIds = userRepository.findAllAdminIds();
        for (UUID adminId : adminIds) {
            notificationService.sendSlaNotification(
                    ticket, NotificationType.SLA_BREACH, adminId
            );
        }

        // 觸發 Email（非同步）
        emailNotificationService.sendSlaAlertEmail(ticket.getId(), NotificationType.SLA_BREACH);

        log.info("Sent SLA BREACH notifications for ticket: {}", ticket.getId());
    }

    private void sendWarningNotifications(Ticket ticket) {
        // 只通知負責 Agent
        if (ticket.getAssignedTo() != null) {
            notificationService.sendSlaNotification(
                    ticket, NotificationType.SLA_WARNING, ticket.getAssignedTo()
            );
            log.info("Sent SLA WARNING notification for ticket: {} to agent: {}",
                    ticket.getId(), ticket.getAssignedTo());
        } else {
            log.debug("Ticket {} has no assignee, skipping SLA warning", ticket.getId());
            return;
        }

        // 觸發 Email（非同步）
        emailNotificationService.sendSlaAlertEmail(ticket.getId(), NotificationType.SLA_WARNING);
    }
}
