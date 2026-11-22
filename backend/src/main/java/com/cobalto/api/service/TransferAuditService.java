package com.cobalto.api.service;

import com.cobalto.api.domain.TransferAuditEvent;
import com.cobalto.api.repository.TransferAuditEventRepository;
import com.cobalto.api.security.TokenHash;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!migrate")
public class TransferAuditService {
    private final TransferAuditEventRepository repository;

    public TransferAuditService(TransferAuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttempt(UUID actorUserId, UUID idempotencyKey) {
        record(actorUserId, idempotencyKey, "ATTEMPTED", null, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCompleted(UUID actorUserId, UUID idempotencyKey, UUID transactionId) {
        record(actorUserId, idempotencyKey, "COMPLETED", transactionId, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID actorUserId, UUID idempotencyKey, String failureCode) {
        record(actorUserId, idempotencyKey, "FAILED", null, failureCode);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordReplay(UUID actorUserId, UUID idempotencyKey, UUID transactionId) {
        record(actorUserId, idempotencyKey, "REPLAYED", transactionId, null);
    }

    private void record(UUID actorUserId, UUID idempotencyKey, String type,
                        UUID transactionId, String failureCode) {
        repository.save(new TransferAuditEvent(
            actorUserId,
            TokenHash.sha256(idempotencyKey.toString()),
            type,
            transactionId,
            failureCode,
            Instant.now()));
    }
}
