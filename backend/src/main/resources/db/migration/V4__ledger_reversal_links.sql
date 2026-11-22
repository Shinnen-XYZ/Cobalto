ALTER TABLE ledger_transactions
    ADD COLUMN reversal_of_transaction_id UUID,
    ADD CONSTRAINT fk_ledger_reversal_of
        FOREIGN KEY (reversal_of_transaction_id) REFERENCES ledger_transactions(id),
    ADD CONSTRAINT uq_ledger_reversal_of UNIQUE (reversal_of_transaction_id),
    ADD CONSTRAINT ck_ledger_reversal_type CHECK (
        (type = 'REVERSAL' AND reversal_of_transaction_id IS NOT NULL)
        OR (type <> 'REVERSAL' AND reversal_of_transaction_id IS NULL)
    ),
    ADD CONSTRAINT ck_ledger_reversal_not_self CHECK (
        reversal_of_transaction_id IS NULL OR reversal_of_transaction_id <> id
    );

CREATE FUNCTION enforce_ledger_reversal_metadata() RETURNS TRIGGER AS $$
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
        OR original_type = 'REVERSAL' THEN
        RAISE EXCEPTION 'Invalid reversal reference for ledger transaction %', NEW.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_ledger_reversal_metadata
BEFORE INSERT ON ledger_transactions
FOR EACH ROW EXECUTE FUNCTION enforce_ledger_reversal_metadata();

CREATE FUNCTION enforce_ledger_reversal_entries() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.reversal_of_transaction_id IS NULL THEN
        RETURN NEW;
    END IF;

    IF EXISTS (
        WITH expected AS (
            SELECT account_id,
                   CASE entry_type WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END AS entry_type,
                   SUM(amount) AS amount
            FROM ledger_entries
            WHERE transaction_id = NEW.reversal_of_transaction_id
            GROUP BY account_id, entry_type
        ),
        actual AS (
            SELECT account_id, entry_type, SUM(amount) AS amount
            FROM ledger_entries
            WHERE transaction_id = NEW.id
            GROUP BY account_id, entry_type
        )
        SELECT 1 FROM (
            (SELECT * FROM expected EXCEPT SELECT * FROM actual)
            UNION ALL
            (SELECT * FROM actual EXCEPT SELECT * FROM expected)
        ) difference
    ) THEN
        RAISE EXCEPTION 'Ledger reversal % does not exactly reverse transaction %',
            NEW.id, NEW.reversal_of_transaction_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ct_ledger_reversal_entries
AFTER INSERT ON ledger_transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_ledger_reversal_entries();
