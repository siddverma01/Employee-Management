package com.emplmgt.dto;

import com.emplmgt.entity.NotificationType;

import java.time.Instant;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record Response(
            Long id,
            String title,
            String body,
            NotificationType type,
            String link,
            boolean read,
            Instant createdAt) {
    }

    public record UnreadCount(long count) {
    }

    public record BroadcastRequest(
            @jakarta.validation.constraints.NotBlank String title,
            String body,
            NotificationType type) {
    }
}