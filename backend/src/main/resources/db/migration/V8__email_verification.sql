ALTER TABLE app_users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN email_verified_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_app_users_email_verification CHECK (
        (email_verified AND email_verified_at IS NOT NULL)
        OR (NOT email_verified AND email_verified_at IS NULL)
    );

CREATE TABLE auth_email_verification_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_auth_email_verification_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_auth_email_verification_state CHECK (
        consumed_at IS NULL OR revoked_at IS NULL
    )
);

CREATE UNIQUE INDEX uq_auth_email_verification_active_user
    ON auth_email_verification_tokens(user_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
CREATE INDEX ix_auth_email_verification_user_created
    ON auth_email_verification_tokens(user_id, created_at DESC);
CREATE INDEX ix_auth_email_verification_expiry
    ON auth_email_verification_tokens(expires_at);

CREATE TABLE auth_email_verification_outbox (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    token_id UUID NOT NULL UNIQUE
        REFERENCES auth_email_verification_tokens(id),
    encrypted_token BYTEA,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED')),
    attempts SMALLINT NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    created_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    CONSTRAINT ck_auth_email_verification_outbox_payload CHECK (
        (status = 'PENDING' AND encrypted_token IS NOT NULL)
        OR (status <> 'PENDING' AND encrypted_token IS NULL)
    ),
    CONSTRAINT ck_auth_email_verification_outbox_sent CHECK (
        (status = 'SENT' AND sent_at IS NOT NULL)
        OR (status <> 'SENT' AND sent_at IS NULL)
    )
);

CREATE INDEX ix_auth_email_verification_outbox_pending
    ON auth_email_verification_outbox(next_attempt_at, created_at)
    WHERE status = 'PENDING';
CREATE INDEX ix_auth_email_verification_outbox_user
    ON auth_email_verification_outbox(user_id, created_at DESC);

CREATE FUNCTION prevent_runtime_email_verification() RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'cobalto_app' THEN
        IF TG_OP = 'INSERT' AND NEW.email_verified THEN
            RAISE EXCEPTION 'Email verification must use a verification token'
                USING ERRCODE = '42501';
        END IF;
        IF TG_OP = 'UPDATE'
            AND NEW.email_verified IS DISTINCT FROM OLD.email_verified
            AND NEW.email_verified THEN
            RAISE EXCEPTION 'Email verification must use a verification token'
                USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_app_users_email_verification_guard
BEFORE INSERT OR UPDATE OF email_verified ON app_users
FOR EACH ROW EXECUTE FUNCTION prevent_runtime_email_verification();

CREATE FUNCTION prevent_email_verification_token_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'cobalto_app'
        AND (
            NEW.id IS DISTINCT FROM OLD.id
            OR NEW.user_id IS DISTINCT FROM OLD.user_id
            OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.consumed_at IS DISTINCT FROM OLD.consumed_at
            OR (OLD.revoked_at IS NOT NULL
                AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at)
        ) THEN
        RAISE EXCEPTION 'Email verification tokens are immutable'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_auth_email_verification_tokens_protect
BEFORE UPDATE ON auth_email_verification_tokens
FOR EACH ROW EXECUTE FUNCTION prevent_email_verification_token_mutation();

DO $create_confirmation_function$
DECLARE
    app_schema TEXT := current_schema();
BEGIN
    EXECUTE format($ddl$
        CREATE FUNCTION %I.consume_email_verification_token(p_token_hash VARCHAR)
        RETURNS UUID
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, %I, pg_temp
        AS $function$
        DECLARE
            verified_user_id UUID;
        BEGIN
            IF p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$' THEN
                RETURN NULL;
            END IF;

            SELECT user_id INTO verified_user_id
            FROM auth_email_verification_tokens
            WHERE token_hash = p_token_hash
              AND consumed_at IS NULL
              AND revoked_at IS NULL
              AND expires_at > clock_timestamp()
            FOR UPDATE;

            IF NOT FOUND THEN
                RETURN NULL;
            END IF;

            UPDATE app_users
            SET email_verified = TRUE,
                email_verified_at = clock_timestamp()
            WHERE id = verified_user_id
              AND email_verified = FALSE;

            IF NOT FOUND THEN
                RETURN NULL;
            END IF;

            UPDATE auth_email_verification_tokens
            SET consumed_at = clock_timestamp()
            WHERE token_hash = p_token_hash;

            UPDATE auth_email_verification_tokens
            SET revoked_at = clock_timestamp()
            WHERE user_id = verified_user_id
              AND id <> (
                  SELECT id FROM auth_email_verification_tokens
                  WHERE token_hash = p_token_hash
              )
              AND consumed_at IS NULL
              AND revoked_at IS NULL;

            UPDATE auth_email_verification_outbox
            SET status = 'CANCELLED',
                encrypted_token = NULL
            WHERE user_id = verified_user_id
              AND status = 'PENDING';

            RETURN verified_user_id;
        END;
        $function$
    $ddl$, app_schema, app_schema);
END;
$create_confirmation_function$;

DO $replace_transfer$
DECLARE
    app_schema TEXT := current_schema();
BEGIN
    EXECUTE format($ddl$
        CREATE OR REPLACE FUNCTION %I.post_wallet_transfer(
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
                OR debit_currency <> credit_currency
                OR NOT EXISTS (
                    SELECT 1 FROM app_users
                    WHERE id = credit_owner AND email_verified
                ) THEN
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
END;
$replace_transfer$;

REVOKE ALL ON FUNCTION consume_email_verification_token(VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_runtime_email_verification() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_email_verification_token_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION post_wallet_transfer(UUID, VARCHAR, VARCHAR, UUID, UUID, NUMERIC)
    FROM PUBLIC;

DO $privileges$
DECLARE
    app_schema TEXT := current_schema();
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cobalto_app') THEN
        EXECUTE format(
            'REVOKE INSERT, UPDATE, DELETE ON TABLE %I.auth_email_verification_tokens, '
            '%I.auth_email_verification_outbox FROM cobalto_app',
            app_schema, app_schema);
        EXECUTE format(
            'REVOKE INSERT, UPDATE ON TABLE %I.app_users FROM cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT INSERT (id, full_name, email, password_hash, created_at, '
            'mfa_enabled, mfa_secret_encrypted, mfa_pending_secret_encrypted, '
            'mfa_pending_expires_at, mfa_last_counter, mfa_failed_attempts, '
            'mfa_locked_until) ON TABLE %I.app_users TO cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT UPDATE (full_name, email, password_hash, created_at, '
            'mfa_enabled, mfa_secret_encrypted, mfa_pending_secret_encrypted, '
            'mfa_pending_expires_at, mfa_last_counter, mfa_failed_attempts, '
            'mfa_locked_until) ON TABLE %I.app_users TO cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT SELECT ON TABLE %I.auth_email_verification_tokens TO cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT INSERT (id, user_id, token_hash, created_at, expires_at) '
            'ON TABLE %I.auth_email_verification_tokens TO cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT UPDATE (revoked_at) ON TABLE %I.auth_email_verification_tokens '
            'TO cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT SELECT ON TABLE %I.auth_email_verification_outbox TO cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT INSERT (id, user_id, token_id, encrypted_token, status, attempts, '
            'created_at, next_attempt_at) ON TABLE %I.auth_email_verification_outbox '
            'TO cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT UPDATE (encrypted_token, status, attempts, next_attempt_at, sent_at, '
            'last_error_code) ON TABLE %I.auth_email_verification_outbox TO cobalto_app',
            app_schema);
        EXECUTE format(
            'REVOKE DELETE ON TABLE %I.auth_email_verification_tokens, '
            '%I.auth_email_verification_outbox FROM cobalto_app',
            app_schema, app_schema);
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION %I.consume_email_verification_token(VARCHAR) '
            'TO cobalto_app',
            app_schema);
    END IF;
END;
$privileges$;
