package com.emplmgt.controller;

import com.emplmgt.dto.NotificationDtos;
import com.emplmgt.entity.Notification;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final SecurityUtils securityUtils;

    @GetMapping
    public ResponseEntity<List<NotificationDtos.Response>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = securityUtils.currentUserId();
        List<NotificationDtos.Response> result = notificationService.findForUser(userId, page, Math.min(size, 100))
                .stream().map(this::toResponse).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/unread-count")
    public ResponseEntity<NotificationDtos.UnreadCount> unreadCount() {
        return ResponseEntity.ok(new NotificationDtos.UnreadCount(
                notificationService.unreadCount(securityUtils.currentUserId())));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(securityUtils.currentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<NotificationDtos.UnreadCount> markAllRead() {
        int updated = notificationService.markAllRead(securityUtils.currentUserId());
        return ResponseEntity.ok(new NotificationDtos.UnreadCount(0));
    }

    private NotificationDtos.Response toResponse(Notification n) {
        return new NotificationDtos.Response(n.getId(), n.getTitle(), n.getBody(), n.getType(),
                n.getLink(), n.getReadAt() != null, n.getCreatedAt());
    }
}