package com.pk.support_ticket_api.sla.service;

import com.pk.support_ticket_api.notifications.domain.Notification;
import com.pk.support_ticket_api.notifications.domain.NotificationType;
import com.pk.support_ticket_api.notifications.repository.NotificationRepository;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailNotificationService {

    private final JavaMailSender mailSender;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    /**
     * 非同步發送 SLA Email 通知
     */
    @Async("notificationExecutor")
    public void sendSlaAlertEmail(UUID ticketId, NotificationType type) {
        log.debug("Sending SLA email for ticket: {}, type: {}", ticketId, type);

        List<Notification> notifications = notificationRepository
                .findByTicketIdAndType(ticketId, type);

        for (Notification notification : notifications) {
            User recipient = userRepository.findById(notification.getRecipientId())
                    .orElse(null);

            if (recipient == null || recipient.getEmail() == null) {
                log.warn("Cannot send email: recipient or email is null for notification: {}",
                        notification.getId());
                continue;
            }

            try {
                sendEmail(recipient.getEmail(), notification);
            } catch (Exception e) {
                log.error("Failed to send email to {}: {}",
                        recipient.getEmail(), e.getMessage());
            }
        }
    }

    private void sendEmail(String to, Notification notification) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject(notification.getTitle());
        message.setText(notification.getMessage());
        mailSender.send(message);

        log.info("Email sent successfully to: {}", to);
    }
}
