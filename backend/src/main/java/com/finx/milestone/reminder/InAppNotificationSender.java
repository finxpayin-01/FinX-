package com.finx.milestone.reminder;

import com.finx.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InAppNotificationSender implements NotificationChannelSender {

    private static final Logger log = LoggerFactory.getLogger(InAppNotificationSender.class);

    private final NotificationService notificationService;

    public InAppNotificationSender(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public void send(UUID recipientId, String title, String message, String route) {
        log.info("Sending in-app notification to user={}: title=[{}] route=[{}]", recipientId, title, route);
        notificationService.sendNotification(recipientId, title, message, route);
    }
}
