DO $migration$
DECLARE
    app_schema TEXT := current_schema();
BEGIN
    IF app_schema IS NULL THEN
        RAISE EXCEPTION 'A default schema is required for ledger posting functions';
    END IF;

    EXECUTE format($ddl$
        CREATE FUNCTION %I.post_sandbox_initial_credit(
            p_actor_user_id UUID,
            p_wallet_account_id UUID
        ) RETURNS UUID
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, %I, pg_temp
        AS $function$
        DECLARE
            existing_id UUID;
            wallet_currency VARCHAR(3);
            transaction_id UUID;
            system_account_id CONSTANT UUID :=
                '00000000-0000-0000-0000-000000000001';
        BEGIN
            IF p_actor_user_id IS NULL OR p_wallet_account_id IS NULL THEN
                RAISE EXCEPTION 'Sandbox credit requires an actor and wallet'
                    USING ERRCODE = '22023';
            END IF;

            PERFORM pg_advisory_xact_lock(hashtextextended(account_id::TEXT, 0))
            FROM (
                SELECT id AS account_id
                FROM ledger_accounts
                WHERE id IN (system_account_id, p_wallet_account_id)
                ORDER BY id
            ) locked_accounts;

            SELECT currency INTO wallet_currency
            FROM ledger_accounts
            WHERE id = p_wallet_account_id
              AND account_type = 'WALLET'
              AND owner_user_id = p_actor_user_id;

            IF NOT FOUND OR wallet_currency <> 'GTQ' THEN
                RAISE EXCEPTION 'Invalid sandbox wallet owner or currency'
                    USING ERRCODE = '42501';
            END IF;

            SELECT id INTO existing_id
            FROM ledger_transactions
            WHERE actor_user_id = p_actor_user_id
              AND idempotency_key = 'signup-' || p_actor_user_id::TEXT;

            IF FOUND THEN
                IF EXISTS (
                    SELECT 1
                    FROM ledger_transactions t
                    WHERE t.id = existing_id
                      AND t.type = 'SANDBOX_CREDIT'
                      AND t.currency = 'GTQ'
                      AND (
                          SELECT count(*)
                          FROM ledger_entries e
                          WHERE e.transaction_id = t.id
                      ) = 2
                      AND EXISTS (
                          SELECT 1 FROM ledger_entries e
                          WHERE e.transaction_id = t.id
                            AND e.account_id = system_account_id
                            AND e.entry_type = 'DEBIT'
                            AND e.amount = 100.00
                      )
                      AND EXISTS (
                          SELECT 1 FROM ledger_entries e
                          WHERE e.transaction_id = t.id
                            AND e.account_id = p_wallet_account_id
                            AND e.entry_type = 'CREDIT'
                            AND e.amount = 100.00
                      )
                ) THEN
                    RETURN existing_id;
                END IF;

                RAISE EXCEPTION 'Sandbox credit idempotency key was reused'
                    USING ERRCODE = '23505';
            END IF;

            IF EXISTS (
                SELECT 1
                FROM ledger_transactions
                WHERE actor_user_id = p_actor_user_id
                  AND type = 'SANDBOX_CREDIT'
            ) THEN
                RAISE EXCEPTION 'Initial sandbox credit was already posted'
                    USING ERRCODE = '23505';
            END IF;

            IF NOT EXISTS (
                SELECT 1
                FROM ledger_accounts
                WHERE id = system_account_id
                  AND account_type = 'SYSTEM'
                  AND owner_user_id IS NULL
                  AND currency = 'GTQ'
            ) THEN
                RAISE EXCEPTION 'Sandbox issuance account is unavailable';
            END IF;

            INSERT INTO ledger_transactions
                (id, type, actor_user_id, idempotency_key, currency, description, created_at)
            VALUES
                (gen_random_uuid(), 'SANDBOX_CREDIT', p_actor_user_id,
                 'signup-' || p_actor_user_id::TEXT, 'GTQ',
                 'Saldo inicial de prueba', clock_timestamp())
            RETURNING id INTO transaction_id;

            INSERT INTO ledger_entries (transaction_id, account_id, entry_type, amount)
            VALUES
                (transaction_id, system_account_id, 'DEBIT', 100.00),
                (transaction_id, p_wallet_account_id, 'CREDIT', 100.00);

            RETURN transaction_id;
        END;
        $function$
    $ddl$, app_schema, app_schema);

    EXECUTE format($ddl$
        CREATE FUNCTION %I.post_wallet_transfer(
            p_actor_user_id UUID,
            p_idempotency_key VARCHAR,
            p_request_fingerprint VARCHAR,
            p_debit_account_id UUID,
            p_credit_account_id UUID,
            p_amount NUMERIC
        ) RETURNS UUID
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, %I, pg_temp
        AS $function$
        DECLARE
            debit_owner UUID;
            credit_owner UUID;
            debit_currency VARCHAR(3);
            credit_currency VARCHAR(3);
            existing_id UUID;
            existing_type VARCHAR(32);
            existing_fingerprint VARCHAR(64);
            current_balance NUMERIC(19, 4);
            transaction_id UUID;
        BEGIN
            IF p_actor_user_id IS NULL
                OR p_debit_account_id IS NULL
                OR p_credit_account_id IS NULL
                OR p_debit_account_id = p_credit_account_id
                OR p_idempotency_key IS NULL
                OR length(p_idempotency_key) NOT BETWEEN 1 AND 80
                OR p_request_fingerprint IS NULL
                OR p_request_fingerprint !~ '^[0-9a-f]{64}$'
                OR p_amount IS NULL
                OR p_amount <= 0
                OR p_amount > 999999999999999.99
                OR p_amount <> trunc(p_amount, 2) THEN
                RAISE EXCEPTION 'Invalid wallet transfer parameters'
                    USING ERRCODE = '22023';
            END IF;

            SELECT owner_user_id, currency INTO debit_owner, debit_currency
            FROM ledger_accounts
            WHERE id = p_debit_account_id AND account_type = 'WALLET';

            SELECT owner_user_id, currency INTO credit_owner, credit_currency
            FROM ledger_accounts
            WHERE id = p_credit_account_id AND account_type = 'WALLET';

            IF debit_owner IS NULL OR debit_owner <> p_actor_user_id
                OR credit_owner IS NULL OR credit_owner = p_actor_user_id
                OR debit_currency <> 'GTQ' OR credit_currency <> 'GTQ'
                OR debit_currency <> credit_currency THEN
                RAISE EXCEPTION 'Transfer wallets do not match the authorized actor'
                    USING ERRCODE = '42501';
            END IF;

            PERFORM pg_advisory_xact_lock(hashtextextended(id::TEXT, 0))
            FROM (
                SELECT account_id AS id
                FROM (VALUES (p_debit_account_id), (p_credit_account_id)) AS wallet_ids(account_id)
                ORDER BY account_id
            ) locked_wallets;

            SELECT id, type, request_fingerprint
            INTO existing_id, existing_type, existing_fingerprint
            FROM ledger_transactions
            WHERE actor_user_id = p_actor_user_id
              AND idempotency_key = p_idempotency_key;

            IF FOUND THEN
                IF existing_type = 'TRANSFER'
                    AND existing_fingerprint = p_request_fingerprint
                    AND (
                        SELECT count(*)
                        FROM ledger_entries e
                        WHERE e.transaction_id = existing_id
                    ) = 2
                    AND EXISTS (
                        SELECT 1 FROM ledger_entries e
                        WHERE e.transaction_id = existing_id
                          AND e.account_id = p_debit_account_id
                          AND e.entry_type = 'DEBIT'
                          AND e.amount = p_amount
                    )
                    AND EXISTS (
                        SELECT 1 FROM ledger_entries e
                        WHERE e.transaction_id = existing_id
                          AND e.account_id = p_credit_account_id
                          AND e.entry_type = 'CREDIT'
                          AND e.amount = p_amount
                    ) THEN
                    RETURN existing_id;
                END IF;

                RAISE EXCEPTION 'Transfer idempotency key was reused'
                    USING ERRCODE = '23505';
            END IF;

            SELECT coalesce(
                sum(CASE WHEN e.entry_type = 'CREDIT' THEN e.amount ELSE -e.amount END), 0
            )
            INTO current_balance
            FROM ledger_entries e
            WHERE e.account_id = p_debit_account_id;

            IF current_balance < p_amount THEN
                RAISE EXCEPTION 'Insufficient funds'
                    USING ERRCODE = 'P0001';
            END IF;

            INSERT INTO ledger_transactions
                (id, type, actor_user_id, idempotency_key, request_fingerprint,
                 currency, description, created_at)
            VALUES
                (gen_random_uuid(), 'TRANSFER', p_actor_user_id, p_idempotency_key,
                 p_request_fingerprint, 'GTQ', 'Transferencia entre usuarios',
                 clock_timestamp())
            RETURNING id INTO transaction_id;

            INSERT INTO ledger_entries (transaction_id, account_id, entry_type, amount)
            VALUES
                (transaction_id, p_debit_account_id, 'DEBIT', p_amount),
                (transaction_id, p_credit_account_id, 'CREDIT', p_amount);

            RETURN transaction_id;
        END;
        $function$
    $ddl$, app_schema, app_schema);

    EXECUTE format($ddl$
        CREATE FUNCTION %I.post_wallet_transfer_reversal(
            p_original_transaction_id UUID,
            p_authorized_by_user_id UUID,
            p_idempotency_key VARCHAR,
            p_request_fingerprint VARCHAR
        ) RETURNS UUID
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, %I, pg_temp
        AS $function$
        DECLARE
            original_actor UUID;
            original_currency VARCHAR(3);
            original_type VARCHAR(32);
            debit_account UUID;
            credit_account UUID;
            debit_count INTEGER;
            credit_count INTEGER;
            entry_count INTEGER;
            amount NUMERIC(19, 4);
            current_credit_balance NUMERIC(19, 4);
            existing_id UUID;
            existing_original UUID;
            existing_authorizer UUID;
            existing_fingerprint VARCHAR(64);
            reversal_id UUID;
        BEGIN
            IF p_original_transaction_id IS NULL
                OR p_authorized_by_user_id IS NULL
                OR p_idempotency_key IS NULL
                OR length(p_idempotency_key) NOT BETWEEN 1 AND 80
                OR p_request_fingerprint IS NULL
                OR p_request_fingerprint !~ '^[0-9a-f]{64}$' THEN
                RAISE EXCEPTION 'Invalid reversal parameters'
                    USING ERRCODE = '22023';
            END IF;

            PERFORM pg_advisory_xact_lock(hashtextextended(
                'ledger-transaction:' || p_original_transaction_id::TEXT, 0));

            SELECT actor_user_id, currency, type
            INTO original_actor, original_currency, original_type
            FROM ledger_transactions
            WHERE id = p_original_transaction_id;

            IF NOT FOUND OR original_type <> 'TRANSFER' OR original_currency <> 'GTQ' THEN
                RAISE EXCEPTION 'Only completed GTQ transfers can be reversed'
                    USING ERRCODE = '23514';
            END IF;

            IF NOT EXISTS (
                SELECT 1
                FROM app_user_permissions
                WHERE user_id = p_authorized_by_user_id
                  AND permission_code = 'TRANSFER_REVERSAL'
                  AND revoked_at IS NULL
            ) THEN
                RAISE EXCEPTION 'Transfer reversal permission is required'
                    USING ERRCODE = '42501';
            END IF;

            SELECT id, reversal_of_transaction_id, reversal_authorized_by_user_id,
                   request_fingerprint
            INTO existing_id, existing_original, existing_authorizer, existing_fingerprint
            FROM ledger_transactions
            WHERE actor_user_id = original_actor
              AND idempotency_key = p_idempotency_key;

            IF FOUND THEN
                IF existing_original = p_original_transaction_id
                    AND existing_authorizer = p_authorized_by_user_id
                    AND existing_fingerprint = p_request_fingerprint THEN
                    RETURN existing_id;
                END IF;

                RAISE EXCEPTION 'Reversal idempotency key was reused'
                    USING ERRCODE = '23505';
            END IF;

            IF EXISTS (
                SELECT 1 FROM ledger_transactions
                WHERE reversal_of_transaction_id = p_original_transaction_id
            ) THEN
                RAISE EXCEPTION 'Transfer has already been reversed'
                    USING ERRCODE = '23505';
            END IF;

            SELECT
                count(*) FILTER (
                    WHERE e.entry_type = 'DEBIT'
                      AND a.account_type = 'WALLET'
                      AND a.owner_user_id = original_actor
                      AND a.currency = original_currency
                ),
                count(*) FILTER (
                    WHERE e.entry_type = 'CREDIT'
                      AND a.account_type = 'WALLET'
                      AND a.owner_user_id <> original_actor
                      AND a.currency = original_currency
                ),
                count(*),
                (array_agg(e.account_id) FILTER (WHERE e.entry_type = 'DEBIT'))[1],
                (array_agg(e.account_id) FILTER (WHERE e.entry_type = 'CREDIT'))[1],
                max(e.amount) FILTER (WHERE e.entry_type = 'DEBIT')
            INTO debit_count, credit_count, entry_count,
                 debit_account, credit_account, amount
            FROM ledger_entries e
            JOIN ledger_accounts a ON a.id = e.account_id
            WHERE e.transaction_id = p_original_transaction_id;

            IF entry_count <> 2 OR debit_count <> 1 OR credit_count <> 1
                OR amount IS NULL OR amount <= 0 THEN
                RAISE EXCEPTION 'Transfer ledger entries are invalid for reversal'
                    USING ERRCODE = '23514';
            END IF;

            PERFORM pg_advisory_xact_lock(hashtextextended(account_id::TEXT, 0))
            FROM (
                SELECT id AS account_id
                FROM ledger_accounts
                WHERE id IN (debit_account, credit_account)
                ORDER BY id
            ) locked_accounts;

            SELECT coalesce(
                sum(CASE WHEN e.entry_type = 'CREDIT' THEN e.amount ELSE -e.amount END), 0
            )
            INTO current_credit_balance
            FROM ledger_entries e
            WHERE e.account_id = credit_account;

            IF current_credit_balance < amount THEN
                RAISE EXCEPTION 'Insufficient funds for transfer reversal'
                    USING ERRCODE = 'P0001';
            END IF;

            INSERT INTO ledger_transactions
                (id, type, actor_user_id, idempotency_key, request_fingerprint,
                 currency, description, created_at, reversal_of_transaction_id,
                 reversal_authorized_by_user_id)
            VALUES
                (gen_random_uuid(), 'REVERSAL', original_actor, p_idempotency_key,
                 p_request_fingerprint, original_currency,
                 'Reversión de transferencia ' || p_original_transaction_id::TEXT,
                 clock_timestamp(), p_original_transaction_id, p_authorized_by_user_id)
            RETURNING id INTO reversal_id;

            INSERT INTO ledger_entries (transaction_id, account_id, entry_type, amount)
            SELECT reversal_id, e.account_id,
                   CASE e.entry_type WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END,
                   e.amount
            FROM ledger_entries e
            WHERE e.transaction_id = p_original_transaction_id;

            RETURN reversal_id;
        END;
        $function$
    $ddl$, app_schema, app_schema);
END;
$migration$;

CREATE FUNCTION enforce_ledger_transaction_business_type() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.type NOT IN ('SANDBOX_CREDIT', 'TRANSFER', 'REVERSAL') THEN
        RAISE EXCEPTION 'Unsupported ledger transaction type';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_ledger_transaction_business_type
BEFORE INSERT ON ledger_transactions
FOR EACH ROW EXECUTE FUNCTION enforce_ledger_transaction_business_type();

CREATE UNIQUE INDEX uq_transfer_audit_completed_transaction
    ON transfer_audit_events(transaction_id)
    WHERE event_type = 'COMPLETED' AND transaction_id IS NOT NULL;

CREATE FUNCTION enforce_sandbox_credit_posting() RETURNS TRIGGER AS $$
DECLARE
    posting_count INTEGER;
BEGIN
    IF NEW.type <> 'SANDBOX_CREDIT' THEN
        RETURN NEW;
    END IF;

    SELECT count(*)
    INTO posting_count
    FROM ledger_entries e
    JOIN ledger_accounts a ON a.id = e.account_id
    WHERE e.transaction_id = NEW.id
      AND NEW.currency = 'GTQ'
      AND (
          (e.account_id = '00000000-0000-0000-0000-000000000001'
              AND a.account_type = 'SYSTEM'
              AND e.entry_type = 'DEBIT'
              AND e.amount = 100.00)
          OR
          (a.account_type = 'WALLET'
              AND a.owner_user_id = NEW.actor_user_id
              AND a.currency = 'GTQ'
              AND e.entry_type = 'CREDIT'
              AND e.amount = 100.00)
      );

    IF posting_count <> 2
        OR (SELECT count(*) FROM ledger_entries WHERE transaction_id = NEW.id) <> 2 THEN
        RAISE EXCEPTION 'Sandbox credit must be the fixed initial wallet credit';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ct_sandbox_credit_posting
AFTER INSERT ON ledger_transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_sandbox_credit_posting();

CREATE FUNCTION enforce_transfer_posting_support() RETURNS TRIGGER AS $$
DECLARE
    debit_wallet_owner UUID;
    credit_wallet_owner UUID;
    entry_count INTEGER;
BEGIN
    IF NEW.type <> 'TRANSFER' THEN
        RETURN NEW;
    END IF;

    SELECT
        count(*),
        (min(a.owner_user_id::TEXT) FILTER (
            WHERE e.entry_type = 'DEBIT' AND a.account_type = 'WALLET'
        ))::UUID,
        (min(a.owner_user_id::TEXT) FILTER (
            WHERE e.entry_type = 'CREDIT' AND a.account_type = 'WALLET'
        ))::UUID
    INTO entry_count, debit_wallet_owner, credit_wallet_owner
    FROM ledger_entries e
    JOIN ledger_accounts a ON a.id = e.account_id
    WHERE e.transaction_id = NEW.id
      AND a.currency = NEW.currency;

    IF entry_count <> 2
        OR debit_wallet_owner IS DISTINCT FROM NEW.actor_user_id
        OR credit_wallet_owner IS NULL
        OR credit_wallet_owner = NEW.actor_user_id
        OR NOT EXISTS (
            SELECT 1 FROM transfer_audit_events
            WHERE transaction_id = NEW.id
              AND actor_user_id = NEW.actor_user_id
              AND event_type = 'COMPLETED'
        )
        OR NOT EXISTS (
            SELECT 1 FROM app_notifications
            WHERE transaction_id = NEW.id
              AND user_id = debit_wallet_owner
              AND notification_type = 'TRANSFER_SENT'
        )
        OR NOT EXISTS (
            SELECT 1 FROM app_notifications
            WHERE transaction_id = NEW.id
              AND user_id = credit_wallet_owner
              AND notification_type = 'TRANSFER_RECEIVED'
        )
        OR (
            SELECT count(*) FROM app_notifications
            WHERE transaction_id = NEW.id
              AND notification_type IN ('TRANSFER_SENT', 'TRANSFER_RECEIVED')
        ) <> 2 THEN
        RAISE EXCEPTION 'A transfer requires valid wallet owners, audit, and notifications';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ct_transfer_posting_support
AFTER INSERT ON ledger_transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_transfer_posting_support();

DO $trigger_security$
DECLARE
    app_schema TEXT := current_schema();
BEGIN
    EXECUTE format(
        'ALTER FUNCTION %I.enforce_transfer_posting_support() SECURITY DEFINER '
        'SET search_path = pg_catalog, %I, pg_temp',
        app_schema, app_schema);
END;
$trigger_security$;

DO $privileges$
DECLARE
    app_schema TEXT := current_schema();
    sequence_name TEXT;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cobalto_app') THEN
        EXECUTE format(
            'REVOKE INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I FROM cobalto_app',
            app_schema);
        EXECUTE format(
            'REVOKE INSERT, UPDATE, DELETE ON TABLE %I.ledger_transactions FROM cobalto_app',
            app_schema);
        EXECUTE format(
            'REVOKE INSERT, UPDATE, DELETE ON TABLE %I.ledger_entries FROM cobalto_app',
            app_schema);
        EXECUTE format(
            'REVOKE UPDATE ON TABLE %I.app_notifications FROM cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT UPDATE (read_at) ON TABLE %I.app_notifications TO cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT SELECT ON ALL TABLES IN SCHEMA %I TO cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT INSERT ON TABLE %I.app_users, %I.ledger_accounts, '
            '%I.auth_refresh_sessions, %I.auth_mfa_login_challenges, '
            '%I.auth_mfa_recovery_codes, %I.app_notifications, '
            '%I.transfer_audit_events, %I.transfer_reversal_audits TO cobalto_app',
            app_schema, app_schema, app_schema, app_schema, app_schema,
            app_schema, app_schema, app_schema);
        EXECUTE format(
            'GRANT UPDATE ON TABLE %I.app_users, %I.auth_refresh_sessions, '
            '%I.auth_mfa_login_challenges TO cobalto_app',
            app_schema, app_schema, app_schema);
        EXECUTE format(
            'GRANT UPDATE, DELETE ON TABLE %I.auth_mfa_recovery_codes TO cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT SELECT ON TABLE %I.app_user_permissions TO cobalto_app',
            app_schema);
        EXECUTE format(
            'REVOKE SELECT ON TABLE %I.transfer_audit_events FROM cobalto_app',
            app_schema);
        EXECUTE format(
            'REVOKE INSERT, UPDATE, DELETE ON TABLE %I.app_user_permissions FROM cobalto_app',
            app_schema);

        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES FOR ROLE cobalto_migrator IN SCHEMA %I '
            'REVOKE INSERT, UPDATE, DELETE ON TABLES FROM cobalto_app',
            app_schema);
        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES FOR ROLE cobalto_migrator IN SCHEMA %I '
            'REVOKE USAGE, SELECT ON SEQUENCES FROM cobalto_app',
            app_schema);
        EXECUTE format(
            'REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM cobalto_app',
            app_schema);

        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION %I.post_sandbox_initial_credit(UUID, UUID) TO cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION %I.post_wallet_transfer(UUID, VARCHAR, VARCHAR, UUID, UUID, NUMERIC) TO cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION %I.post_wallet_transfer_reversal(UUID, UUID, VARCHAR, VARCHAR) TO cobalto_app',
            app_schema);

        sequence_name := pg_get_serial_sequence(
            format('%I.ledger_entries', app_schema), 'id');
        IF sequence_name IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM cobalto_app', sequence_name);
        END IF;
    END IF;
END;
$privileges$;

REVOKE ALL ON FUNCTION post_sandbox_initial_credit(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION post_wallet_transfer(UUID, VARCHAR, VARCHAR, UUID, UUID, NUMERIC) FROM PUBLIC;
REVOKE ALL ON FUNCTION post_wallet_transfer_reversal(UUID, UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION enforce_ledger_transaction_business_type() FROM PUBLIC;
REVOKE ALL ON FUNCTION enforce_sandbox_credit_posting() FROM PUBLIC;
REVOKE ALL ON FUNCTION enforce_transfer_posting_support() FROM PUBLIC;
