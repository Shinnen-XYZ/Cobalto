DO $$
BEGIN
    IF EXISTS (
        SELECT t.id
        FROM ledger_transactions t
        LEFT JOIN ledger_entries e ON e.transaction_id = t.id
        LEFT JOIN ledger_accounts a ON a.id = e.account_id
        GROUP BY t.id
        HAVING COALESCE(SUM(e.amount) FILTER (WHERE e.entry_type = 'DEBIT'), 0) <= 0
            OR COALESCE(SUM(e.amount) FILTER (WHERE e.entry_type = 'CREDIT'), 0) <= 0
            OR COALESCE(SUM(e.amount) FILTER (WHERE e.entry_type = 'DEBIT'), 0)
                <> COALESCE(SUM(e.amount) FILTER (WHERE e.entry_type = 'CREDIT'), 0)
            OR BOOL_OR(a.currency <> t.currency)
    ) THEN
        RAISE EXCEPTION 'Existing ledger transactions violate posting invariants';
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION enforce_nonempty_balanced_ledger_transaction() RETURNS TRIGGER AS $$
DECLARE
    debit_total NUMERIC(19, 4);
    credit_total NUMERIC(19, 4);
BEGIN
    SELECT
        COALESCE(SUM(amount) FILTER (WHERE entry_type = 'DEBIT'), 0),
        COALESCE(SUM(amount) FILTER (WHERE entry_type = 'CREDIT'), 0)
    INTO debit_total, credit_total
    FROM ledger_entries
    WHERE transaction_id = NEW.id;

    IF debit_total <= 0 OR credit_total <= 0 OR debit_total <> credit_total THEN
        RAISE EXCEPTION 'Ledger transaction % must have balanced debit and credit entries', NEW.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ct_ledger_transaction_has_balanced_entries
AFTER INSERT ON ledger_transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_nonempty_balanced_ledger_transaction();
