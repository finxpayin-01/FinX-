package com.finx.notification.service;

import com.finx.notification.dto.response.NotificationResponse;
import com.finx.notification.entity.Notification;
import com.finx.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public void sendNotification(UUID recipientUserId, String title, String message, String route) {
        try {
            Notification notification = new Notification(recipientUserId, title, message, route);
            notificationRepository.save(notification);
            log.info("Notification sent to user={}: {}", recipientUserId, title);
        } catch (Exception e) {
            log.error("Failed to persist notification for user={}: {}", recipientUserId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getNotificationsForUser(UUID userId) {
        return notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(userId).stream()
                .map(NotificationResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public void markAsRead(UUID notificationId, UUID userId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            if (n.getRecipientUserId().equals(userId)) {
                n.setRead(true);
                notificationRepository.save(n);
            }
        });
    }

    @Transactional
    public void markAllAsRead(UUID userId) {
        List<Notification> unread = notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(userId);
        unread.forEach(n -> n.setRead(true));
        notificationRepository.saveAll(unread);
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return notificationRepository.countByRecipientUserIdAndIsReadFalse(userId);
    }
}
