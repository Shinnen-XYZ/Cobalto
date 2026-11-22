CREATE TABLE app_notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    transaction_id UUID NOT NULL REFERENCES ledger_transactions(id),
    notification_type VARCHAR(32) NOT NULL
        CHECK (notification_type IN ('TRANSFER_SENT', 'TRANSFER_RECEIVED')),
    message VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    CONSTRAINT uq_app_notification_transaction
        UNIQUE (user_id, transaction_id, notification_type)
);

CREATE INDEX ix_app_notifications_user_created
    ON app_notifications(user_id, created_at DESC);

CREATE FUNCTION protect_app_notification() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE'
        OR NEW.id IS DISTINCT FROM OLD.id
        OR NEW.user_id IS DISTINCT FROM OLD.user_id
        OR NEW.transaction_id IS DISTINCT FROM OLD.transaction_id
        OR NEW.notification_type IS DISTINCT FROM OLD.notification_type
        OR NEW.message IS DISTINCT FROM OLD.message
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR (OLD.read_at IS NOT NULL AND NEW.read_at IS DISTINCT FROM OLD.read_at) THEN
        RAISE EXCEPTION 'Notification content is immutable';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_app_notifications_protect
BEFORE UPDATE OR DELETE ON app_notifications
FOR EACH ROW EXECUTE FUNCTION protect_app_notification();

CREATE TABLE transfer_audit_events (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL REFERENCES app_users(id),
    idempotency_key_hash VARCHAR(64) NOT NULL,
    event_type VARCHAR(16) NOT NULL
        CHECK (event_type IN ('ATTEMPTED', 'COMPLETED', 'FAILED', 'REPLAYED')),
    transaction_id UUID,
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_transfer_audit_failure_code CHECK (
        (event_type = 'FAILED' AND failure_code IS NOT NULL)
        OR (event_type <> 'FAILED' AND failure_code IS NULL)
    )
);

CREATE INDEX ix_transfer_audit_actor_created
    ON transfer_audit_events(actor_user_id, created_at DESC);
CREATE INDEX ix_transfer_audit_idempotency
    ON transfer_audit_events(actor_user_id, idempotency_key_hash, created_at DESC);

CREATE FUNCTION reject_transfer_audit_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Transfer audit events are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_transfer_audit_events_immutable
BEFORE UPDATE OR DELETE ON transfer_audit_events
FOR EACH ROW EXECUTE FUNCTION reject_transfer_audit_mutation();

DO $$
BEGIN
    IF EXISTS (
        SELECT a.id
        FROM ledger_accounts a
        LEFT JOIN ledger_entries e ON e.account_id = a.id
        WHERE a.account_type = 'WALLET'
        GROUP BY a.id
        HAVING COALESCE(
            SUM(CASE WHEN e.entry_type = 'CREDIT' THEN e.amount ELSE -e.amount END), 0
        ) < 0
    ) THEN
        RAISE EXCEPTION 'Existing wallet balances cannot be negative';
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION enforce_nonnegative_wallet_balance() RETURNS TRIGGER AS $$
DECLARE
    wallet_account RECORD;
    resulting_balance NUMERIC(19, 4);
BEGIN
    FOR wallet_account IN
        SELECT DISTINCT a.id
        FROM ledger_entries e
        JOIN ledger_accounts a ON a.id = e.account_id
        WHERE e.transaction_id = NEW.id
          AND a.account_type = 'WALLET'
        ORDER BY a.id
    LOOP
        PERFORM pg_advisory_xact_lock(hashtextextended(wallet_account.id::text, 0));
        SELECT COALESCE(
            SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END), 0
        )
        INTO resulting_balance
        FROM ledger_entries
        WHERE account_id = wallet_account.id;

        IF resulting_balance < 0 THEN
            RAISE EXCEPTION 'Ledger transaction % would make wallet % negative',
                NEW.id, wallet_account.id;
        END IF;
    END LOOP;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ct_ledger_wallet_balance_nonnegative
AFTER INSERT ON ledger_transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_nonnegative_wallet_balance();
