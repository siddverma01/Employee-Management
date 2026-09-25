package com.emplmgt.controller;

import com.emplmgt.dto.NotificationDtos;
import com.emplmgt.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final NotificationService notificationService;

    @PostMapping("/broadcast")
    public ResponseEntity<Void> broadcast(@Valid @RequestBody NotificationDtos.BroadcastRequest request) {
        notificationService.broadcast(request.title(), request.body(), request.type(), null);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}