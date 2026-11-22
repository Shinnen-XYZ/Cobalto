package com.cobalto.api.service;

import com.cobalto.api.exception.DomainException;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("!migrate")
public class UserPermissionService {
    public static final String TRANSFER_REVERSAL = "TRANSFER_REVERSAL";

    private final JdbcTemplate jdbcTemplate;

    public UserPermissionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean hasPermission(UUID userId, String permission) {
        Boolean granted = jdbcTemplate.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM app_user_permissions
                WHERE user_id = ? AND permission_code = ? AND revoked_at IS NULL
            )
            """, Boolean.class, userId, permission);
        return Boolean.TRUE.equals(granted);
    }

    public List<String> findActivePermissions(UUID userId) {
        return jdbcTemplate.queryForList("""
            SELECT permission_code
            FROM app_user_permissions
            WHERE user_id = ? AND revoked_at IS NULL
            """, String.class, userId);
    }

    public void requirePermission(UUID userId, String permission) {
        if (!hasPermission(userId, permission)) {
            throw new DomainException(HttpStatus.FORBIDDEN, "PERMISSION_REQUIRED",
                "No tienes permiso para revertir transferencias");
        }
    }
}
