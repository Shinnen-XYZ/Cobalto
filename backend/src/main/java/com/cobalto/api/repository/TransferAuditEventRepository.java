package com.cobalto.api.repository;

import com.cobalto.api.domain.TransferAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TransferAuditEventRepository extends JpaRepository<TransferAuditEvent, UUID> {}
