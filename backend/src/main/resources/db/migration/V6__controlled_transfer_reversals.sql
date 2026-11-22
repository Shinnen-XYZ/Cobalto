CREATE TABLE app_user_permissions (
    user_id UUID NOT NULL REFERENCES app_users(id),
    permission_code VARCHAR(64) NOT NULL
        CHECK (permission_code IN ('TRANSFER_REVERSAL')),
    granted_by_user_id UUID NOT NULL REFERENCES app_users(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_by_user_id UUID REFERENCES app_users(id),
    revoked_at TIMESTAMPTZ,
    CONSTRAINT pk_app_user_permissions PRIMARY KEY (user_id, permission_code),
    CONSTRAINT ck_app_user_permission_revocation CHECK (
        (revoked_at IS NULL AND revoked_by_user_id IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by_user_id IS NOT NULL)
    )
);

CREATE INDEX ix_app_user_permissions_active
    ON app_user_permissions(user_id, permission_code)
    WHERE revoked_at IS NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cobalto_app') THEN
        REVOKE INSERT, UPDATE, DELETE ON app_user_permissions FROM cobalto_app;
    END IF;
END;
$$;

ALTER TABLE ledger_transactions
    ADD COLUMN reversal_authorized_by_user_id UUID REFERENCES app_users(id),
    ADD CONSTRAINT ck_ledger_reversal_authorizer CHECK (
        (type = 'REVERSAL' AND reversal_authorized_by_user_id IS NOT NULL)
        OR (type <> 'REVERSAL' AND reversal_authorized_by_user_id IS NULL)
    );

CREATE OR REPLACE FUNCTION enforce_ledger_reversal_metadata() RETURNS TRIGGER AS $$
DECLARE
    original_actor UUID;
    original_currency VARCHAR(3);
    original_type VARCHAR(32);
BEGIN
    IF NEW.reversal_of_transaction_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT actor_user_id, currency, type
    INTO original_actor, original_currency, original_type
    FROM ledger_transactions
    WHERE id = NEW.reversal_of_transaction_id;

    IF original_actor IS NULL
        OR original_actor <> NEW.actor_user_id
        OR original_currency <> NEW.currency
        OR original_type <> 'TRANSFER'
        OR NOT EXISTS (
            SELECT 1
            FROM app_user_permissions
            WHERE user_id = NEW.reversal_authorized_by_user_id
              AND permission_code = 'TRANSFER_REVERSAL'
              AND revoked_at IS NULL
        ) THEN
        RAISE EXCEPTION 'Invalid reversal reference or authorization for ledger transaction %',
            NEW.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

ALTER TABLE app_notifications
    DROP CONSTRAINT app_notifications_notification_type_check,
    ADD CONSTRAINT ck_app_notifications_type CHECK (
        notification_type IN (
            'TRANSFER_SENT', 'TRANSFER_RECEIVED', 'TRANSFER_REVERSED'
        )
    );

CREATE TABLE transfer_reversal_audits (
    id UUID PRIMARY KEY,
    original_transaction_id UUID NOT NULL UNIQUE
        REFERENCES ledger_transactions(id),
    reversal_transaction_id UUID NOT NULL UNIQUE
        REFERENCES ledger_transactions(id),
    authorized_by_user_id UUID NOT NULL REFERENCES app_users(id),
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) > 0),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_transfer_reversal_audit_idempotency
        UNIQUE (authorized_by_user_id, idempotency_key_hash)
);

CREATE INDEX ix_transfer_reversal_audit_authorizer_created
    ON transfer_reversal_audits(authorized_by_user_id, created_at DESC);

CREATE FUNCTION validate_transfer_reversal_audit() RETURNS TRIGGER AS $$
DECLARE
    reversal_original UUID;
    reversal_authorizer UUID;
BEGIN
    SELECT reversal_of_transaction_id, reversal_authorized_by_user_id
    INTO reversal_original, reversal_authorizer
    FROM ledger_transactions
    WHERE id = NEW.reversal_transaction_id
      AND type = 'REVERSAL';

    IF reversal_original IS DISTINCT FROM NEW.original_transaction_id
        OR reversal_authorizer IS DISTINCT FROM NEW.authorized_by_user_id THEN
        RAISE EXCEPTION 'Reversal audit does not match the authorized ledger reversal';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM ledger_transactions
        WHERE id = NEW.original_transaction_id AND type = 'TRANSFER'
    ) THEN
        RAISE EXCEPTION 'Only completed transfers can have a reversal audit';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_transfer_reversal_audit_validate
BEFORE INSERT ON transfer_reversal_audits
FOR EACH ROW EXECUTE FUNCTION validate_transfer_reversal_audit();

CREATE FUNCTION reject_transfer_reversal_audit_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Transfer reversal audit events are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_transfer_reversal_audit_immutable
BEFORE UPDATE OR DELETE ON transfer_reversal_audits
FOR EACH ROW EXECUTE FUNCTION reject_transfer_reversal_audit_mutation();

CREATE FUNCTION enforce_transfer_reversal_support() RETURNS TRIGGER AS $$
DECLARE
    expected_parties INTEGER;
    reversal_notifications INTEGER;
BEGIN
    IF NEW.type <> 'REVERSAL' THEN
        RETURN NEW;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM transfer_reversal_audits
        WHERE original_transaction_id = NEW.reversal_of_transaction_id
          AND reversal_transaction_id = NEW.id
          AND authorized_by_user_id = NEW.reversal_authorized_by_user_id
    ) THEN
        RAISE EXCEPTION 'A reversal requires its immutable authorization audit';
    END IF;

    SELECT count(DISTINCT a.owner_user_id)
    INTO expected_parties
    FROM ledger_entries e
    JOIN ledger_accounts a ON a.id = e.account_id
    WHERE e.transaction_id = NEW.reversal_of_transaction_id
      AND a.account_type = 'WALLET';

    SELECT count(*)
    INTO reversal_notifications
    FROM app_notifications
    WHERE transaction_id = NEW.id
      AND notification_type = 'TRANSFER_REVERSED';

    IF expected_parties <> 2
        OR reversal_notifications <> 2
        OR EXISTS (
            SELECT 1
            FROM ledger_entries e
            JOIN ledger_accounts a ON a.id = e.account_id
            WHERE e.transaction_id = NEW.reversal_of_transaction_id
              AND a.account_type = 'WALLET'
              AND NOT EXISTS (
                  SELECT 1
                  FROM app_notifications n
                  WHERE n.transaction_id = NEW.id
                    AND n.notification_type = 'TRANSFER_REVERSED'
                    AND n.user_id = a.owner_user_id
              )
        ) THEN
        RAISE EXCEPTION 'A reversal requires notifications for both wallet owners';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ct_transfer_reversal_support
AFTER INSERT ON ledger_transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_transfer_reversal_support();
