package com.finx.notification.controller;

import com.finx.common.response.ApiResponse;
import com.finx.notification.dto.response.NotificationResponse;
import com.finx.notification.service.NotificationService;
import com.finx.security.service.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "User notifications management endpoints")
@SecurityRequirement(name = "Bearer Authentication")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @Operation(summary = "Get current user notifications", description = "Retrieves all notifications for the authenticated user")
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getNotifications(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<NotificationResponse> notifications = notificationService.getNotificationsForUser(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(notifications));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Get unread notifications count", description = "Retrieves count of unread notifications for authenticated user")
    public ResponseEntity<ApiResponse<Long>> getUnreadCount(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        long count = notificationService.getUnreadCount(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    @PatchMapping("/{id}/read")
    @Operation(summary = "Mark notification as read", description = "Marks a specific notification as read")
    public ResponseEntity<ApiResponse<Void>> markAsRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        notificationService.markAsRead(id, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Notification marked as read", null));
    }

    @PatchMapping("/read-all")
    @Operation(summary = "Mark all notifications as read", description = "Marks all notifications for current user as read")
    public ResponseEntity<ApiResponse<Void>> markAllAsRead(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        notificationService.markAllAsRead(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("All notifications marked as read", null));
    }
}
