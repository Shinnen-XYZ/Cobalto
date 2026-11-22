package com.cobalto.api.service;

import com.cobalto.api.domain.AppNotification;
import com.cobalto.api.dto.NotificationResponse;
import com.cobalto.api.exception.DomainException;
import com.cobalto.api.repository.AppNotificationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!migrate")
public class NotificationService {
    private final AppNotificationRepository repository;

    public NotificationService(AppNotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyTransfer(
        UUID senderId,
        UUID recipientId,
        UUID transactionId,
        String senderName,
        String recipientName,
        String currency,
        BigDecimal amount,
        Instant createdAt
    ) {
        String formattedAmount = amount.setScale(2).toPlainString();
        repository.saveAllAndFlush(List.of(
            new AppNotification(senderId, transactionId, "TRANSFER_SENT",
                "Enviaste " + currency + " " + formattedAmount + " a " + recipientName,
                createdAt),
            new AppNotification(recipientId, transactionId, "TRANSFER_RECEIVED",
                "Recibiste " + currency + " " + formattedAmount + " de " + senderName,
                createdAt)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyTransferReversed(
                UUID senderId,
                UUID recipientId,
                UUID reversalTransactionId,
                String senderName,
                String recipientName,
                String currency,
                BigDecimal amount,
                Instant createdAt
    ) {
                String formattedAmount = amount.setScale(2).toPlainString();
                repository.saveAllAndFlush(List.of(
                    new AppNotification(senderId, reversalTransactionId, "TRANSFER_REVERSED",
                        "Se revirtió una transferencia de " + currency + " " + formattedAmount
                            + " relacionada contigo",
                        createdAt),
                    new AppNotification(recipientId, reversalTransactionId, "TRANSFER_REVERSED",
                        "Se revirtió una transferencia de " + currency + " " + formattedAmount
                            + " relacionada contigo",
                        createdAt)));
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(UUID userId) {
        return repository.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public NotificationResponse markRead(UUID userId, UUID notificationId) {
        repository.markReadIfUnread(notificationId, userId, Instant.now());
        var notification = repository.findByIdAndUserId(notificationId, userId)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND,
                "NOTIFICATION_NOT_FOUND", "No se encontró la notificación"));
        return toResponse(notification);
    }

    private NotificationResponse toResponse(AppNotification notification) {
        return new NotificationResponse(
            notification.getId(),
            notification.getTransactionId(),
            notification.getNotificationType(),
            notification.getMessage(),
            notification.getCreatedAt(),
            notification.getReadAt());
    }
}
