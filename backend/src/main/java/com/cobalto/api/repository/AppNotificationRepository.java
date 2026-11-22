package com.cobalto.api.repository;

import com.cobalto.api.domain.AppNotification;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppNotificationRepository extends JpaRepository<AppNotification, UUID> {
    List<AppNotification> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<AppNotification> findByIdAndUserId(UUID id, UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AppNotification n
        SET n.readAt = :readAt
        WHERE n.id = :id AND n.userId = :userId AND n.readAt IS NULL
        """)
    int markReadIfUnread(
        @Param("id") UUID id,
        @Param("userId") UUID userId,
        @Param("readAt") java.time.Instant readAt
    );
}
