package com.cobalto.api.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PostgresLedgerIntegrationTest {
    private static final String SCHEMA = "cobalto_integration";
    private static EmbeddedPostgres postgres;
    private static DataSource administrator;
    private static DataSource runtime;
    private static UUID legacyUserId;
    private static UUID legacyWalletId;
    private static UUID v10ProcessingOutboxId;

    @BeforeAll
    static void startPostgresAndApplyMigrations() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        administrator = postgres.getPostgresDatabase();
        try (Connection connection = administrator.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE cobalto_migrator LOGIN");
            statement.execute("CREATE ROLE cobalto_app LOGIN");
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("REVOKE ALL ON SCHEMA " + SCHEMA + " FROM PUBLIC");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA " + SCHEMA + " TO cobalto_migrator");
            statement.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO cobalto_app");
            statement.execute("GRANT CONNECT ON DATABASE postgres TO cobalto_app, cobalto_migrator");
            statement.execute("""
                ALTER DEFAULT PRIVILEGES FOR ROLE cobalto_migrator IN SCHEMA cobalto_integration
                GRANT SELECT ON TABLES TO cobalto_app
                """);
        }

        DataSource migrator = postgres.getDatabase(
            "cobalto_migrator", "postgres", Map.of("currentSchema", SCHEMA));
        Flyway.configure()
            .dataSource(migrator)
            .locations("classpath:db/migration")
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .target(MigrationVersion.fromVersion("10"))
            .load()
            .migrate();

        runtime = postgres.getDatabase(
            "cobalto_app", "postgres", Map.of("currentSchema", SCHEMA));
        legacyUserId = createUserAndWallet(runtime, false);
        legacyWalletId = walletId(runtime, legacyUserId);
        v10ProcessingOutboxId = seedV10ProcessingOutbox(legacyUserId);

        Flyway.configure()
            .dataSource(migrator)
            .locations("classpath:db/migration")
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .load()
            .migrate();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void flywayCreatesTablesAndRuntimeHasOnlyRequiredLedgerAndNotificationPrivileges()
        throws Exception {
        assertEquals(11, scalarLong(administrator,
            "SELECT count(*) FROM " + SCHEMA + ".flyway_schema_history WHERE success"));
        for (String table : List.of(
            "app_users", "ledger_accounts", "ledger_transactions", "ledger_entries",
            "auth_refresh_sessions", "auth_mfa_login_challenges", "auth_mfa_recovery_codes",
            "app_notifications", "transfer_audit_events", "app_user_permissions",
            "transfer_reversal_audits", "auth_email_verification_tokens",
            "auth_email_verification_outbox")) {
            assertEquals(1, scalarLong(administrator,
                "SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema = ? AND table_name = ?", SCHEMA, table));
        }

        String transactions = SCHEMA + ".ledger_transactions";
        String entries = SCHEMA + ".ledger_entries";
        String notifications = SCHEMA + ".app_notifications";
        assertEquals(0, scalarLong(administrator,
            "SELECT count(*) FROM information_schema.role_table_grants "
                + "WHERE grantee = 'cobalto_app' AND table_schema = ? "
                + "AND table_name IN ('ledger_transactions', 'ledger_entries') "
                + "AND privilege_type IN ('INSERT', 'UPDATE', 'DELETE')",
            SCHEMA));
        assertEquals(1, scalarLong(administrator,
            "SELECT CASE WHEN has_function_privilege('cobalto_app', ?, 'EXECUTE')"
                + " THEN 1 ELSE 0 END",
            SCHEMA + ".post_wallet_transfer(uuid,character varying,character varying,uuid,uuid,numeric)"));
        assertEquals(0, scalarLong(administrator,
            "SELECT CASE WHEN has_table_privilege('cobalto_app', ?, 'INSERT')"
                + " THEN 1 ELSE 0 END", transactions));
        assertEquals(0, scalarLong(administrator,
            "SELECT CASE WHEN has_table_privilege('cobalto_app', ?, 'INSERT')"
                + " THEN 1 ELSE 0 END", entries));
        assertEquals(0, scalarLong(administrator,
            "SELECT CASE WHEN has_table_privilege('cobalto_app', ?, 'UPDATE')"
                + " THEN 1 ELSE 0 END", notifications));
        assertEquals(1, scalarLong(administrator,
            "SELECT CASE WHEN has_column_privilege('cobalto_app', ?, 'read_at', 'UPDATE')"
                + " THEN 1 ELSE 0 END",
            notifications));
        assertEquals(0, scalarLong(administrator,
            "SELECT CASE WHEN has_column_privilege('cobalto_app', ?, 'message', 'UPDATE')"
                + " THEN 1 ELSE 0 END",
            notifications));
        assertEquals(0, scalarLong(administrator,
            "SELECT CASE WHEN has_column_privilege('cobalto_app', ?, "
                + "'email_verified', 'UPDATE') THEN 1 ELSE 0 END",
            SCHEMA + ".app_users"));
        assertEquals(0, scalarLong(administrator,
            "SELECT CASE WHEN has_column_privilege('cobalto_app', ?, "
                + "'email_verified', 'INSERT') THEN 1 ELSE 0 END",
            SCHEMA + ".app_users"));
        assertEquals(1, scalarLong(administrator,
            "SELECT CASE WHEN has_table_privilege('cobalto_app', ?, 'SELECT')"
                + " THEN 1 ELSE 0 END",
            SCHEMA + ".auth_email_verification_tokens"));
        assertEquals(0, scalarLong(administrator,
            "SELECT CASE WHEN has_table_privilege('cobalto_app', ?, 'DELETE')"
                + " THEN 1 ELSE 0 END",
            SCHEMA + ".auth_email_verification_tokens"));
        assertEquals(1, scalarLong(administrator,
            "SELECT CASE WHEN has_column_privilege('cobalto_app', ?, "
                + "'lease_until', 'UPDATE') THEN 1 ELSE 0 END",
            SCHEMA + ".auth_email_verification_outbox"));
        assertEquals(0, scalarLong(administrator,
            "SELECT CASE WHEN has_column_privilege('cobalto_app', ?, "
                + "'recipient_email', 'UPDATE') THEN 1 ELSE 0 END",
            SCHEMA + ".auth_email_verification_outbox"));

        UUID userId = createUserAndWallet(runtime);
        grantReversalPermission(userId);
        assertEquals(1, scalarLong(runtime,
            "SELECT count(*) FROM " + SCHEMA + ".app_user_permissions "
                + "WHERE user_id = ? AND permission_code = 'TRANSFER_REVERSAL'",
            userId));
        assertThrows(SQLException.class, () -> execute(runtime, """
            INSERT INTO app_user_permissions (user_id, permission_code, granted_by_user_id)
            VALUES (?, 'TRANSFER_REVERSAL', ?)
            """, userId, userId));
        assertThrows(SQLException.class, () -> execute(runtime, """
            INSERT INTO ledger_transactions
                (id, type, actor_user_id, idempotency_key, currency, description, created_at)
            VALUES (?, 'TRANSFER', ?, ?, 'GTQ', 'forbidden direct insert', ?)
            """, UUID.randomUUID(), userId, UUID.randomUUID().toString(),
            Timestamp.from(Instant.now())));
        assertThrows(SQLException.class, () -> execute(runtime, """
            INSERT INTO ledger_entries (transaction_id, account_id, entry_type, amount)
            VALUES (?, ?, 'DEBIT', 1.00)
            """, UUID.randomUUID(), UUID.randomUUID()));
        assertThrows(SQLException.class, () -> scalarLong(runtime,
            "SELECT count(*) FROM transfer_audit_events"));
        assertThrows(SQLException.class, () -> execute(runtime,
            "UPDATE ledger_entries SET amount = 0.0001"));
        assertThrows(SQLException.class, () -> execute(runtime,
            "CREATE TABLE runtime_cannot_create (id integer)"));

        UUID refreshId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        UUID recoveryId = UUID.randomUUID();
        Instant now = Instant.now();
        execute(runtime, """
            INSERT INTO auth_refresh_sessions
                (id, user_id, family_id, token_hash, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, refreshId, userId, familyId, "a".repeat(64),
            Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)));
        execute(runtime,
            "UPDATE auth_refresh_sessions SET revoked_at = ? WHERE id = ?",
            Timestamp.from(now), refreshId);
        execute(runtime, """
            INSERT INTO auth_mfa_login_challenges
                (id, user_id, token_hash, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?)
            """, challengeId, userId, "b".repeat(64),
            Timestamp.from(now), Timestamp.from(now.plusSeconds(300)));
        execute(runtime,
            "UPDATE auth_mfa_login_challenges SET consumed_at = ? WHERE id = ?",
            Timestamp.from(now), challengeId);
        execute(runtime, """
            INSERT INTO auth_mfa_recovery_codes (id, user_id, code_hash, created_at)
            VALUES (?, ?, ?, ?)
            """, recoveryId, userId, "c".repeat(64), Timestamp.from(now));
        execute(runtime, "DELETE FROM auth_mfa_recovery_codes WHERE id = ?", recoveryId);
    }

    @Test
    void v11PreservesExistingSandboxLedgerAndUnverifiedUsers()
        throws Exception {
        assertEquals(0, scalarLong(administrator,
            "SELECT CASE WHEN email_verified THEN 1 ELSE 0 END FROM "
                + SCHEMA + ".app_users WHERE id = ?",
            legacyUserId));
        assertEquals(new BigDecimal("100.0000"),
            walletBalance(runtime, legacyWalletId));
        assertEquals(1, scalarLong(administrator,
            "SELECT count(*) FROM " + SCHEMA
                + ".ledger_transactions WHERE actor_user_id = ? AND type = 'SANDBOX_CREDIT'",
            legacyUserId));
        assertEquals(2, scalarLong(administrator,
            "SELECT count(*) FROM " + SCHEMA
                + ".ledger_entries e JOIN " + SCHEMA
                + ".ledger_transactions t ON t.id = e.transaction_id "
                + "WHERE t.actor_user_id = ? AND t.type = 'SANDBOX_CREDIT'",
            legacyUserId));
    }

    @Test
    void v11CountsAnExistingV10ProcessingLeaseAsAnAttempt() throws Exception {
        assertEquals(1, scalarLong(administrator,
            "SELECT count(*) FROM " + SCHEMA
                + ".auth_email_verification_outbox WHERE id = ? "
                + "AND status = 'PROCESSING' AND attempts = 3",
            v10ProcessingOutboxId));
    }

    @Test
    void runtimeCannotPostDirectTransferToUnverifiedWallet() throws Exception {
        UUID senderId = createUserAndWallet(runtime);
        UUID recipientId = createUserAndWallet(runtime, false);
        UUID senderWalletId = walletId(runtime, senderId);
        UUID recipientWalletId = walletId(runtime, recipientId);
        String idempotencyKey = "unverified-" + UUID.randomUUID();

        try (Connection connection = runtime.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE " + SCHEMA
                     + ".app_users SET email_verified = TRUE WHERE id = ?")) {
            statement.setObject(1, recipientId);
            assertThrows(SQLException.class, statement::executeUpdate);
        }

        assertThrows(SQLException.class, () -> callTransfer(runtime, senderId,
            idempotencyKey, sha256Hex("unverified-recipient"), senderWalletId,
            recipientWalletId, BigDecimal.ONE));

        assertEquals(new BigDecimal("100.0000"), walletBalance(runtime, senderWalletId));
        assertEquals(new BigDecimal("100.0000"), walletBalance(runtime, recipientWalletId));
        assertEquals(0, scalarLong(administrator,
            "SELECT count(*) FROM " + SCHEMA
                + ".ledger_transactions WHERE actor_user_id = ? AND idempotency_key = ?",
            senderId, idempotencyKey));
    }

    @Test
    void runtimeEmailChangeInvalidatesVerificationAndCancelsPendingDelivery()
        throws Exception {
        UUID userId = createUserAndWallet(runtime);
        UUID walletId = walletId(runtime, userId);
        UUID tokenId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        Instant now = Instant.now();
        execute(runtime, """
            INSERT INTO auth_email_verification_tokens
                (id, user_id, token_hash, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?)
            """, tokenId, userId, sha256Hex("previous-email-token"),
            Timestamp.from(now), Timestamp.from(now.plusSeconds(1800)));
        execute(runtime, """
            INSERT INTO auth_email_verification_outbox
                (id, user_id, token_id, recipient_email, encrypted_token, status, attempts,
                 created_at, next_attempt_at)
            VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
            """, outboxId, userId, tokenId, userId + "@example.test", new byte[] {1, 2, 3},
            Timestamp.from(now), Timestamp.from(now));

        execute(runtime, "UPDATE app_users SET email = ? WHERE id = ?",
            "changed-" + userId + "@example.test", userId);

        assertEquals(0, scalarLong(administrator,
            "SELECT CASE WHEN email_verified THEN 1 ELSE 0 END FROM "
                + SCHEMA + ".app_users WHERE id = ?", userId));
        assertEquals(1, scalarLong(administrator,
            "SELECT CASE WHEN email_verified_at IS NULL THEN 1 ELSE 0 END FROM "
                + SCHEMA + ".app_users WHERE id = ?", userId));
        assertEquals(1, scalarLong(administrator,
            "SELECT CASE WHEN revoked_at IS NOT NULL THEN 1 ELSE 0 END FROM "
                + SCHEMA + ".auth_email_verification_tokens WHERE id = ?", tokenId));
        assertEquals(1, scalarLong(administrator,
            "SELECT CASE WHEN status = 'CANCELLED' AND encrypted_token IS NULL "
                + "THEN 1 ELSE 0 END FROM " + SCHEMA
                + ".auth_email_verification_outbox WHERE id = ?", outboxId));
        assertEquals(new BigDecimal("100.0000"), walletBalance(runtime, walletId));
        assertEquals(1, scalarLong(administrator,
            "SELECT count(*) FROM " + SCHEMA
                + ".ledger_transactions WHERE actor_user_id = ?", userId));
        assertEquals(2, scalarLong(administrator,
            "SELECT count(*) FROM " + SCHEMA + ".ledger_entries e JOIN " + SCHEMA
                + ".ledger_transactions t ON t.id = e.transaction_id "
                + "WHERE t.actor_user_id = ?", userId));
    }

    @Test
    void emailChangeCannotPreserveVerificationAndRuntimeCannotSetItDirectly()
        throws Exception {
        UUID userId = createUserAndWallet(runtime);
        execute(administrator, "UPDATE " + SCHEMA
                + ".app_users SET email = ?, email_verified = TRUE, "
                + "email_verified_at = now() WHERE id = ?",
            "replacement-" + userId + "@example.test", userId);
        assertEquals(0, scalarLong(administrator,
            "SELECT CASE WHEN email_verified THEN 1 ELSE 0 END FROM "
                + SCHEMA + ".app_users WHERE id = ?", userId));
        assertEquals(1, scalarLong(administrator,
            "SELECT CASE WHEN email_verified_at IS NULL THEN 1 ELSE 0 END FROM "
                + SCHEMA + ".app_users WHERE id = ?", userId));

        assertThrows(SQLException.class, () -> execute(runtime,
            "UPDATE app_users SET email_verified = TRUE, email_verified_at = now() "
                + "WHERE id = ?", userId));
        assertEquals(0, scalarLong(administrator,
            "SELECT CASE WHEN email_verified THEN 1 ELSE 0 END FROM "
                + SCHEMA + ".app_users WHERE id = ?", userId));
    }

    @Test
    void confirmationAndEmailChangeUseConsistentLocksAcrossIndependentConnections()
        throws Exception {
        UUID userId = createUserAndWallet(runtime, false);
        UUID tokenId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        String rawToken = "v11-concurrent-confirmation-" + UUID.randomUUID();
        Instant now = Instant.now();
        execute(runtime, """
            INSERT INTO auth_email_verification_tokens
                (id, user_id, token_hash, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?)
            """, tokenId, userId, sha256Hex(rawToken),
            Timestamp.from(now), Timestamp.from(now.plusSeconds(1800)));
        execute(runtime, """
            INSERT INTO auth_email_verification_outbox
                (id, user_id, token_id, recipient_email, encrypted_token, status, attempts,
                 created_at, next_attempt_at)
            VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
            """, outboxId, userId, tokenId, userId + "@example.test", new byte[] {1},
            Timestamp.from(now), Timestamp.from(now));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch userLocked = new CountDownLatch(1);
        CountDownLatch allowEmailChange = new CountDownLatch(1);
        String applicationName = "v11-confirmation-" + UUID.randomUUID();
        try {
            Future<String> emailChange = executor.submit(() -> {
                try (Connection connection = runtime.getConnection()) {
                    connection.setAutoCommit(false);
                    try (PreparedStatement lock = connection.prepareStatement(
                             "SELECT id FROM app_users WHERE id = ? FOR UPDATE")) {
                        lock.setObject(1, userId);
                        try (ResultSet result = lock.executeQuery()) {
                            assertTrue(result.next());
                        }
                    }
                    userLocked.countDown();
                    if (!allowEmailChange.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to change email");
                    }
                    try (PreparedStatement update = connection.prepareStatement(
                             "UPDATE app_users SET email = ? WHERE id = ?")) {
                        update.setString(1, "v11-changed-" + userId + "@example.test");
                        update.setObject(2, userId);
                        update.executeUpdate();
                    }
                    connection.commit();
                    return "COMMITTED";
                } catch (SQLException exception) {
                    return exception.getSQLState();
                }
            });
            assertTrue(userLocked.await(10, TimeUnit.SECONDS));

            Future<UUID> confirmation = executor.submit(() -> {
                try (Connection connection = runtime.getConnection()) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("SET application_name = '" + applicationName + "'");
                    }
                    try (PreparedStatement consume = connection.prepareStatement(
                             "SELECT consume_email_verification_token(?)")) {
                        consume.setString(1, sha256Hex(rawToken));
                        try (ResultSet result = consume.executeQuery()) {
                            assertTrue(result.next());
                            return result.getObject(1, UUID.class);
                        }
                    }
                }
            });
            assertTrue(awaitLockWait(applicationName),
                "Confirmation connection did not wait for the user row lock");

            allowEmailChange.countDown();
            assertEquals("COMMITTED", emailChange.get(15, TimeUnit.SECONDS));
            assertNull(confirmation.get(15, TimeUnit.SECONDS));
        } finally {
            allowEmailChange.countDown();
            executor.shutdownNow();
        }

        assertEquals(0, scalarLong(administrator,
            "SELECT CASE WHEN email_verified THEN 1 ELSE 0 END FROM "
                + SCHEMA + ".app_users WHERE id = ?", userId));
        assertEquals(1, scalarLong(administrator,
            "SELECT CASE WHEN revoked_at IS NOT NULL THEN 1 ELSE 0 END FROM "
                + SCHEMA + ".auth_email_verification_tokens WHERE id = ?", tokenId));
        assertEquals(1, scalarLong(administrator,
            "SELECT CASE WHEN status = 'CANCELLED' AND encrypted_token IS NULL "
                + "THEN 1 ELSE 0 END FROM " + SCHEMA
                + ".auth_email_verification_outbox WHERE id = ?", outboxId));
    }

    @Test
    void ledgerPostingIsBalancedAtomicAndNotificationsOnlyAllowReadAtUpdates() throws Exception {
        UUID senderId = createUserAndWallet(runtime);
        UUID senderWalletId = walletId(runtime, senderId);
        UUID recipientId = createUserAndWallet(runtime);
        UUID recipientWalletId = walletId(runtime, recipientId);
        UUID transactionId = postTransfer(runtime, senderId,
            "transfer-" + UUID.randomUUID(), senderWalletId, recipientId,
            recipientWalletId, new BigDecimal("12.50"));

        assertEquals(new BigDecimal("87.5000"), walletBalance(runtime, senderWalletId));
        assertEquals(new BigDecimal("112.5000"), walletBalance(runtime, recipientWalletId));
        assertEquals(2, scalarLong(administrator, "SELECT count(*) FROM " + SCHEMA
            + ".ledger_entries WHERE transaction_id = ?", transactionId));
        assertEquals(0, scalarLong(administrator, """
            SELECT count(*) FROM (
                SELECT transaction_id
                FROM %s.ledger_entries
                GROUP BY transaction_id
                HAVING coalesce(sum(amount) FILTER (WHERE entry_type = 'DEBIT'), 0)
                    <> coalesce(sum(amount) FILTER (WHERE entry_type = 'CREDIT'), 0)
            ) unbalanced
            """.formatted(SCHEMA)));

        UUID notificationId = UUID.fromString(scalarString(administrator,
            "SELECT id::text FROM " + SCHEMA
                + ".app_notifications WHERE transaction_id = ? AND user_id = ?",
            transactionId, senderId));
        execute(runtime, "UPDATE app_notifications SET read_at = ? WHERE id = ? AND user_id = ?",
            Timestamp.from(Instant.now()), notificationId, senderId);
        assertThrows(SQLException.class, () -> execute(runtime,
            "UPDATE app_notifications SET message = 'changed' WHERE id = ?", notificationId));
        assertThrows(SQLException.class, () -> execute(runtime,
            "UPDATE " + SCHEMA + ".ledger_entries SET amount = 0.0001 WHERE transaction_id = ?",
            transactionId));

        String emptyKey = "empty-" + UUID.randomUUID();
        try (Connection connection = administrator.getConnection()) {
            connection.setSchema(SCHEMA);
            connection.setAutoCommit(false);
            insertTransaction(connection, senderId, emptyKey);
            assertThrows(SQLException.class, connection::commit);
            connection.rollback();
        }
        assertEquals(0, scalarLong(administrator,
            "SELECT count(*) FROM " + SCHEMA + ".ledger_transactions WHERE idempotency_key = ?",
            emptyKey));

        String unbalancedKey = "unbalanced-" + UUID.randomUUID();
        try (Connection connection = administrator.getConnection()) {
            connection.setSchema(SCHEMA);
            connection.setAutoCommit(false);
            UUID invalidId = insertTransaction(connection, senderId, unbalancedKey);
            insertEntry(connection, invalidId, senderWalletId, "DEBIT", new BigDecimal("5.00"));
            insertEntry(connection, invalidId, recipientWalletId, "CREDIT", new BigDecimal("4.00"));
            assertThrows(SQLException.class, connection::commit);
            connection.rollback();
        }
        assertEquals(0, scalarLong(administrator,
            "SELECT count(*) FROM " + SCHEMA + ".ledger_transactions WHERE idempotency_key = ?",
            unbalancedKey));

        String missingSupportKey = "missing-support-" + UUID.randomUUID();
        try (Connection connection = administrator.getConnection()) {
            connection.setSchema(SCHEMA);
            connection.setAutoCommit(false);
            UUID invalidId = insertTransaction(connection, senderId, missingSupportKey);
            insertEntry(connection, invalidId, senderWalletId, "DEBIT", new BigDecimal("1.00"));
            insertEntry(connection, invalidId, recipientWalletId, "CREDIT", new BigDecimal("1.00"));
            assertThrows(SQLException.class, connection::commit);
            connection.rollback();
        }
        assertEquals(0, scalarLong(administrator,
            "SELECT count(*) FROM " + SCHEMA + ".ledger_transactions WHERE idempotency_key = ?",
            missingSupportKey));
    }

    @Test
    void concurrentTransfersSerializeAndIdempotencyDoesNotPostTwice() throws Exception {
        UUID senderId = createUserAndWallet(runtime);
        UUID senderWalletId = walletId(runtime, senderId);
        UUID firstRecipientId = createUserAndWallet(runtime);
        UUID firstRecipientWallet = walletId(runtime, firstRecipientId);
        UUID secondRecipientId = createUserAndWallet(runtime);
        UUID secondRecipientWallet = walletId(runtime, secondRecipientId);
        UUID idempotentRecipientId = createUserAndWallet(runtime);
        UUID idempotentRecipientWallet = walletId(runtime, idempotentRecipientId);

        postTransfer(runtime, senderId, "drain-" + UUID.randomUUID(), senderWalletId,
            firstRecipientId, firstRecipientWallet, new BigDecimal("96.00"));
        List<Boolean> debitResults = runConcurrently(
            () -> attemptTransfer(senderId, senderWalletId, firstRecipientId, firstRecipientWallet,
                new BigDecimal("3.00"), "concurrent-a-" + UUID.randomUUID()),
            () -> attemptTransfer(senderId, senderWalletId, secondRecipientId, secondRecipientWallet,
                new BigDecimal("3.00"), "concurrent-b-" + UUID.randomUUID()));
        assertEquals(1, debitResults.stream().filter(Boolean::booleanValue).count());
        assertEquals(new BigDecimal("1.0000"), walletBalance(runtime, senderWalletId));

        String sharedKey = "same-key-" + UUID.randomUUID();
        UUID firstId = postTransfer(runtime, senderId, sharedKey, senderWalletId,
            idempotentRecipientId, idempotentRecipientWallet, new BigDecimal("0.50"));
        UUID replayId = postTransfer(runtime, senderId, sharedKey, senderWalletId,
            idempotentRecipientId, idempotentRecipientWallet, new BigDecimal("0.50"));
        assertEquals(firstId, replayId);
        assertEquals(new BigDecimal("0.5000"), walletBalance(runtime, senderWalletId));
        assertEquals(1, scalarLong(administrator,
            "SELECT count(*) FROM " + SCHEMA
                + ".ledger_transactions WHERE actor_user_id = ? AND idempotency_key = ?",
            senderId, sharedKey));
    }

    @Test
    void reversalsAreAuthorizedAtomicIdempotentAndExactlyOpposite() throws Exception {
        UUID senderId = createUserAndWallet(runtime);
        UUID senderWalletId = walletId(runtime, senderId);
        UUID recipientId = createUserAndWallet(runtime);
        UUID recipientWalletId = walletId(runtime, recipientId);
        grantReversalPermission(senderId);
        UUID originalId = postTransfer(runtime, senderId,
            "original-" + UUID.randomUUID(), senderWalletId, recipientId,
            recipientWalletId, new BigDecimal("5.00"));
        String reversalKey = "reversal-" + UUID.randomUUID();
        String fingerprint = sha256Hex(originalId + "|" + senderId + "|reason");
        UUID reversalId = postReversal(runtime, originalId, senderId, reversalKey, fingerprint);

        assertEquals(reversalId, callReversal(runtime, originalId, senderId,
            reversalKey, fingerprint));
        assertEquals(new BigDecimal("100.0000"), walletBalance(runtime, senderWalletId));
        assertEquals(new BigDecimal("100.0000"), walletBalance(runtime, recipientWalletId));
        assertEquals(2, scalarLong(administrator, "SELECT count(*) FROM " + SCHEMA
            + ".ledger_entries WHERE transaction_id = ?", originalId));
        assertEquals(2, scalarLong(administrator, """
            SELECT count(*)
            FROM %s.ledger_entries reversal_entry
            JOIN %s.ledger_entries original_entry
              ON original_entry.transaction_id = ?
             AND original_entry.account_id = reversal_entry.account_id
             AND original_entry.amount = reversal_entry.amount
             AND original_entry.entry_type <> reversal_entry.entry_type
            WHERE reversal_entry.transaction_id = ?
            """.formatted(SCHEMA, SCHEMA), originalId, reversalId));
        assertEquals(1, scalarLong(administrator, "SELECT count(*) FROM " + SCHEMA
            + ".transfer_reversal_audits WHERE original_transaction_id = ?",
            originalId));
        assertEquals(2, scalarLong(administrator, "SELECT count(*) FROM " + SCHEMA
            + ".app_notifications WHERE transaction_id = ? AND notification_type = 'TRANSFER_REVERSED'",
            reversalId));

        assertThrows(SQLException.class, () -> callReversal(runtime, originalId, senderId,
            "duplicate-" + UUID.randomUUID(), sha256Hex("different")));
        assertThrows(SQLException.class, () -> callReversal(runtime, UUID.randomUUID(),
            senderId, "missing-" + UUID.randomUUID(), sha256Hex("missing")));

        UUID unauthorizedId = createUserAndWallet(runtime);
        UUID guardedOriginal = postTransfer(runtime, senderId,
            "guarded-" + UUID.randomUUID(), senderWalletId, recipientId,
            recipientWalletId, new BigDecimal("1.00"));
        assertThrows(SQLException.class, () -> callReversal(runtime, guardedOriginal,
            unauthorizedId, "unauthorized-" + UUID.randomUUID(), sha256Hex("unauthorized")));

        UUID concurrentOriginal = postTransfer(runtime, senderId,
            "concurrent-reversal-" + UUID.randomUUID(), senderWalletId, recipientId,
            recipientWalletId, new BigDecimal("1.00"));
        List<Boolean> reversalResults = runConcurrently(
            () -> attemptReversal(concurrentOriginal, senderId,
                "reversal-a-" + UUID.randomUUID()),
            () -> attemptReversal(concurrentOriginal, senderId,
                "reversal-b-" + UUID.randomUUID()));
        assertEquals(1, reversalResults.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, scalarLong(administrator, "SELECT count(*) FROM " + SCHEMA
            + ".ledger_transactions WHERE reversal_of_transaction_id = ?", concurrentOriginal));

        UUID failedOriginal = postTransfer(runtime, senderId,
            "rollback-original-" + UUID.randomUUID(), senderWalletId, recipientId,
            recipientWalletId, new BigDecimal("1.00"));
        String failedKey = "rollback-reversal-" + UUID.randomUUID();
        try (Connection connection = runtime.getConnection()) {
            connection.setAutoCommit(false);
            UUID failedReversal = callReversal(connection, failedOriginal, senderId,
                failedKey, sha256Hex("rollback"));
            insertTransferReversalAudit(connection, failedOriginal, failedReversal, senderId);
            assertThrows(SQLException.class, connection::commit);
            connection.rollback();
        }
        assertEquals(0, scalarLong(administrator, "SELECT count(*) FROM " + SCHEMA
            + ".ledger_transactions WHERE reversal_of_transaction_id = ?", failedOriginal));
    }

    @Test
    void initialSandboxCreditCannotBeReversedAndFundsCannotGoNegative() throws Exception {
        UUID userId = createUserAndWallet(runtime);
        UUID walletId = walletId(runtime, userId);
        UUID initialCreditId = UUID.fromString(scalarString(administrator,
            "SELECT id::text FROM " + SCHEMA
                + ".ledger_transactions WHERE actor_user_id = ? AND type = 'SANDBOX_CREDIT'",
            userId));
        grantReversalPermission(userId);
        assertThrows(SQLException.class, () -> callReversal(runtime, initialCreditId, userId,
            "credit-reversal-" + UUID.randomUUID(), sha256Hex("credit")));

        UUID recipientId = createUserAndWallet(runtime);
        UUID recipientWallet = walletId(runtime, recipientId);
        assertThrows(SQLException.class, () -> callTransfer(runtime, userId,
            "overspend-" + UUID.randomUUID(), sha256Hex("overspend"), walletId,
            recipientWallet, new BigDecimal("100.01")));
        assertEquals(new BigDecimal("100.0000"), walletBalance(runtime, walletId));
        assertEquals(0, scalarLong(administrator, "SELECT count(*) FROM " + SCHEMA
            + ".ledger_transactions WHERE actor_user_id = ? AND type = 'TRANSFER'",
            userId));
    }

    private static UUID createUserAndWallet(DataSource dataSource) throws SQLException {
        return createUserAndWallet(dataSource, true);
    }

    private static UUID createUserAndWallet(DataSource dataSource, boolean verified)
        throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            insertUser(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ledger_accounts (id, account_type, owner_user_id, currency)"
                    + " VALUES (?, 'WALLET', ?, 'GTQ')")) {
                statement.setObject(1, walletId);
                statement.setObject(2, userId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT post_sandbox_initial_credit(?, ?)")) {
                statement.setObject(1, userId);
                statement.setObject(2, walletId);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                }
            }
            connection.commit();
        }
        if (verified) {
            try (Connection connection = administrator.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                     UPDATE %s.app_users
                     SET email_verified = TRUE, email_verified_at = now()
                     WHERE id = ?
                     """.formatted(SCHEMA))) {
                statement.setObject(1, userId);
                statement.executeUpdate();
            }
        }
        return userId;
    }

    private static void insertUser(Connection connection, UUID userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO app_users (id, full_name, email, password_hash, created_at)
            VALUES (?, 'Integration User', ?, 'test-hash', ?)
            """)) {
            statement.setObject(1, userId);
            statement.setString(2, userId + "@example.test");
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private static UUID seedV10ProcessingOutbox(UUID userId) throws SQLException {
        UUID tokenId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        Instant now = Instant.now();
        try (Connection connection = administrator.getConnection()) {
            try (PreparedStatement token = connection.prepareStatement("""
                         INSERT INTO cobalto_integration.auth_email_verification_tokens
                             (id, user_id, token_hash, created_at, expires_at)
                         VALUES (?, ?, ?, ?, ?)
                         """);
                 PreparedStatement outbox = connection.prepareStatement("""
                         INSERT INTO cobalto_integration.auth_email_verification_outbox
                             (id, user_id, token_id, recipient_email, encrypted_token,
                              status, attempts, created_at, next_attempt_at, lease_token, lease_until)
                         VALUES (?, ?, ?, ?, ?, 'PROCESSING', 2, ?, ?, ?, ?)
                         """)) {
                token.setObject(1, tokenId);
                token.setObject(2, userId);
                token.setString(3, sha256Hex("v10-processing-" + outboxId));
                token.setTimestamp(4, Timestamp.from(now));
                token.setTimestamp(5, Timestamp.from(now.plusSeconds(1800)));
                token.executeUpdate();

                outbox.setObject(1, outboxId);
                outbox.setObject(2, userId);
                outbox.setObject(3, tokenId);
                outbox.setString(4, userId + "@example.test");
                outbox.setBytes(5, new byte[] {1, 2, 3});
                outbox.setTimestamp(6, Timestamp.from(now));
                outbox.setTimestamp(7, Timestamp.from(now));
                outbox.setObject(8, UUID.randomUUID());
                outbox.setTimestamp(9, Timestamp.from(now.plusSeconds(300)));
                outbox.executeUpdate();
            }
        }
        return outboxId;
    }

    private static UUID walletId(DataSource dataSource, UUID userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT id FROM ledger_accounts WHERE owner_user_id = ?")) {
            statement.setObject(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static UUID postTransfer(
        DataSource dataSource, UUID senderId, String key, UUID senderWalletId,
        UUID recipientId, UUID recipientWalletId, BigDecimal amount
    ) throws SQLException {
        boolean supportRequired = scalarLong(dataSource,
            "SELECT count(*) FROM ledger_transactions "
                + "WHERE actor_user_id = ? AND idempotency_key = ?",
            senderId, key) == 0;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID transactionId = callTransfer(connection, senderId, key,
                transferFingerprint(senderId, recipientId, amount),
                senderWalletId, recipientWalletId, amount);
            if (supportRequired) {
                insertTransferSupport(connection, transactionId, senderId, recipientId, key);
            }
            connection.commit();
            return transactionId;
        }
    }

    private static boolean attemptTransfer(
        UUID senderId, UUID senderWalletId, UUID recipientId, UUID recipientWalletId,
        BigDecimal amount, String key
    ) throws SQLException {
        try (Connection connection = runtime.getConnection()) {
            connection.setAutoCommit(false);
            try {
                UUID transactionId = callTransfer(connection, senderId, key,
                    transferFingerprint(senderId, recipientId, amount),
                    senderWalletId, recipientWalletId, amount);
                insertTransferSupport(connection, transactionId, senderId, recipientId, key);
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                if ("P0001".equals(exception.getSQLState())) return false;
                throw exception;
            }
        }
    }

    private static UUID callTransfer(
        DataSource dataSource, UUID senderId, String key, String fingerprint,
        UUID senderWalletId, UUID recipientWalletId, BigDecimal amount
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                UUID transactionId = callTransfer(connection, senderId, key, fingerprint,
                    senderWalletId, recipientWalletId, amount);
                connection.commit();
                return transactionId;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static UUID callTransfer(
        Connection connection, UUID senderId, String key, String fingerprint,
        UUID senderWalletId, UUID recipientWalletId, BigDecimal amount
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT post_wallet_transfer(?, ?, ?, ?, ?, ?)")) {
            statement.setObject(1, senderId);
            statement.setString(2, key);
            statement.setString(3, fingerprint);
            statement.setObject(4, senderWalletId);
            statement.setObject(5, recipientWalletId);
            statement.setBigDecimal(6, amount);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static void insertTransferSupport(
        Connection connection, UUID transactionId, UUID senderId, UUID recipientId, String key
    ) throws SQLException {
        Timestamp createdAt = Timestamp.from(Instant.now());
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO transfer_audit_events
                (id, actor_user_id, idempotency_key_hash, event_type, transaction_id, created_at)
            VALUES (?, ?, ?, 'COMPLETED', ?, ?)
            """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, senderId);
            statement.setString(3, sha256Hex(key));
            statement.setObject(4, transactionId);
            statement.setTimestamp(5, createdAt);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO app_notifications
                (id, user_id, transaction_id, notification_type, message, created_at)
            VALUES
                (gen_random_uuid(), ?, ?, 'TRANSFER_SENT', 'test transfer sent', ?),
                (gen_random_uuid(), ?, ?, 'TRANSFER_RECEIVED', 'test transfer received', ?)
            ON CONFLICT (user_id, transaction_id, notification_type) DO NOTHING
            """)) {
            statement.setObject(1, senderId);
            statement.setObject(2, transactionId);
            statement.setTimestamp(3, createdAt);
            statement.setObject(4, recipientId);
            statement.setObject(5, transactionId);
            statement.setTimestamp(6, createdAt);
            statement.executeUpdate();
        }
    }

    private static UUID postReversal(
        DataSource dataSource, UUID originalId, UUID authorizedById, String key,
        String fingerprint
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID reversalId = callReversal(
                connection, originalId, authorizedById, key, fingerprint);
            insertTransferReversalAudit(connection, originalId, reversalId, authorizedById);
            insertTransferReversalNotifications(connection, originalId, reversalId);
            connection.commit();
            return reversalId;
        }
    }

    private static boolean attemptReversal(
        UUID originalId, UUID authorizedById, String key
    ) throws SQLException {
        try (Connection connection = runtime.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String fingerprint = sha256Hex(originalId + "|" + authorizedById + "|" + key);
                UUID reversalId = callReversal(
                    connection, originalId, authorizedById, key, fingerprint);
                insertTransferReversalAudit(connection, originalId, reversalId, authorizedById);
                insertTransferReversalNotifications(connection, originalId, reversalId);
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                if ("23505".equals(exception.getSQLState())) return false;
                throw exception;
            }
        }
    }

    private static UUID callReversal(
        DataSource dataSource, UUID originalId, UUID authorizedById,
        String key, String fingerprint
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                UUID reversalId = callReversal(
                    connection, originalId, authorizedById, key, fingerprint);
                connection.commit();
                return reversalId;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static UUID callReversal(
        Connection connection, UUID originalId, UUID authorizedById,
        String key, String fingerprint
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT post_wallet_transfer_reversal(?, ?, ?, ?)")) {
            statement.setObject(1, originalId);
            statement.setObject(2, authorizedById);
            statement.setString(3, key);
            statement.setString(4, fingerprint);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static void insertTransferReversalAudit(
        Connection connection, UUID originalId, UUID reversalId, UUID authorizedById
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO transfer_reversal_audits
                (id, original_transaction_id, reversal_transaction_id, authorized_by_user_id,
                 idempotency_key_hash, request_fingerprint, reason, created_at)
            VALUES (?, ?, ?, ?, ?, ?, 'integration test reversal', ?)
            """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, originalId);
            statement.setObject(3, reversalId);
            statement.setObject(4, authorizedById);
            statement.setString(5, sha256Hex(UUID.randomUUID().toString()));
            statement.setString(6, sha256Hex(UUID.randomUUID().toString()));
            statement.setTimestamp(7, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private static void insertTransferReversalNotifications(
        Connection connection, UUID originalId, UUID reversalId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO app_notifications
                (id, user_id, transaction_id, notification_type, message, created_at)
            SELECT gen_random_uuid(), a.owner_user_id, ?, 'TRANSFER_REVERSED',
                   'integration test reversal', ?
            FROM ledger_entries e
            JOIN ledger_accounts a ON a.id = e.account_id
            WHERE e.transaction_id = ? AND a.account_type = 'WALLET'
            """)) {
            statement.setObject(1, reversalId);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setObject(3, originalId);
            assertEquals(2, statement.executeUpdate());
        }
    }

    private static UUID insertTransaction(
        Connection connection, UUID actorId, String key
    ) throws SQLException {
        UUID transactionId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO ledger_transactions
                (id, type, actor_user_id, idempotency_key, currency, description, created_at)
            VALUES (?, 'TRANSFER', ?, ?, 'GTQ', 'integration test', ?)
            """)) {
            statement.setObject(1, transactionId);
            statement.setObject(2, actorId);
            statement.setString(3, key);
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return transactionId;
    }

    private static void insertEntry(
        Connection connection, UUID transactionId, UUID accountId, String type, BigDecimal amount
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO ledger_entries (transaction_id, account_id, entry_type, amount)
            VALUES (?, ?, ?, ?)
            """)) {
            statement.setObject(1, transactionId);
            statement.setObject(2, accountId);
            statement.setString(3, type);
            statement.setBigDecimal(4, amount);
            statement.executeUpdate();
        }
    }

    private static void grantReversalPermission(UUID userId) throws SQLException {
        execute(administrator, """
            INSERT INTO %s.app_user_permissions
                (user_id, permission_code, granted_by_user_id)
            VALUES (?, 'TRANSFER_REVERSAL', ?)
            """.formatted(SCHEMA), userId, userId);
    }

    private static BigDecimal walletBalance(DataSource dataSource, UUID walletId)
        throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                SELECT coalesce(
                    sum(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END), 0
                )
                FROM ledger_entries WHERE account_id = ?
                """)) {
            statement.setObject(1, walletId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBigDecimal(1);
            }
        }
    }

    private static long scalarLong(DataSource dataSource, String sql, Object... parameters)
        throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static boolean awaitLockWait(String applicationName) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long waiting = scalarLong(administrator, """
                SELECT count(*) FROM pg_stat_activity
                WHERE application_name = ? AND wait_event_type = 'Lock'
                """, applicationName);
            if (waiting > 0) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static String scalarString(DataSource dataSource, String sql, Object... parameters)
        throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static int execute(DataSource dataSource, String sql, Object... parameters)
        throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters)
        throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private static String transferFingerprint(UUID senderId, UUID recipientId, BigDecimal amount) {
        return sha256Hex(senderId + "|" + recipientId + "|" + amount.stripTrailingZeros());
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the test runtime", exception);
        }
    }

    private static List<Boolean> runConcurrently(Callable<Boolean> first, Callable<Boolean> second)
        throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Callable<Boolean> operation : List.of(first, second)) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test did not start");
                    }
                    return operation.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(
                results.get(0).get(20, TimeUnit.SECONDS),
                results.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
