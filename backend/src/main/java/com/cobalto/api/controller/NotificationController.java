package com.cobalto.api.controller;

import com.cobalto.api.dto.NotificationListResponse;
import com.cobalto.api.dto.NotificationResponse;
import com.cobalto.api.service.NotificationService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!migrate")
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public NotificationListResponse list(@AuthenticationPrincipal UUID userId) {
        return new NotificationListResponse(notificationService.list(userId));
    }

    @PostMapping("/{notificationId}/read")
    public NotificationResponse markRead(
        @AuthenticationPrincipal UUID userId,
        @PathVariable UUID notificationId
    ) {
        return notificationService.markRead(userId, notificationId);
    }
}
