package com.finx.milestone.reminder;

import java.util.UUID;

public interface NotificationChannelSender {

    NotificationChannel getChannel();

    void send(UUID recipientId, String title, String message, String route);
}
