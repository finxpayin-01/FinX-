package com.finx.milestone.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class EmailNotificationSender implements NotificationChannelSender {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationSender.class);

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(UUID recipientId, String title, String message, String route) {
        // Modular placeholder / simulation for SMTP/SES provider
        log.info("DISPATCH_EMAIL to user={}: subject=[{}] body=[{}] actionUrl=[{}]", recipientId, title, message, route);
    }
}
