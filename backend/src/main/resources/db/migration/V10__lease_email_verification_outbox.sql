ALTER TABLE auth_email_verification_outbox
    ADD COLUMN recipient_email VARCHAR(254),
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_until TIMESTAMPTZ;

UPDATE auth_email_verification_outbox o
SET recipient_email = u.email
FROM app_users u
WHERE u.id = o.user_id;

ALTER TABLE auth_email_verification_outbox
    ALTER COLUMN recipient_email SET NOT NULL,
    DROP CONSTRAINT auth_email_verification_outbox_status_check,
    DROP CONSTRAINT ck_auth_email_verification_outbox_payload,
    ADD CONSTRAINT ck_auth_email_verification_outbox_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'CANCELLED')
    ),
    ADD CONSTRAINT ck_auth_email_verification_outbox_payload CHECK (
        (status IN ('PENDING', 'PROCESSING') AND encrypted_token IS NOT NULL)
        OR (status NOT IN ('PENDING', 'PROCESSING') AND encrypted_token IS NULL)
    ),
    ADD CONSTRAINT ck_auth_email_verification_outbox_lease CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)
    );

DROP INDEX ix_auth_email_verification_outbox_pending;
CREATE INDEX ix_auth_email_verification_outbox_pending
    ON auth_email_verification_outbox(next_attempt_at, created_at)
    WHERE status = 'PENDING';
CREATE INDEX ix_auth_email_verification_outbox_expired_lease
    ON auth_email_verification_outbox(lease_until, created_at)
    WHERE status = 'PROCESSING';

DO $replace_email_change_trigger$
DECLARE
    app_schema TEXT := current_schema();
BEGIN
    EXECUTE format($ddl$
        CREATE OR REPLACE FUNCTION %I.invalidate_email_verification_on_email_change()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        SET search_path = pg_catalog, %I, pg_temp
        AS $function$
        BEGIN
            IF NEW.email IS DISTINCT FROM OLD.email THEN
                NEW.email_verified := FALSE;
                NEW.email_verified_at := NULL;

                UPDATE auth_email_verification_tokens
                SET revoked_at = clock_timestamp()
                WHERE user_id = OLD.id
                  AND consumed_at IS NULL
                  AND revoked_at IS NULL;

                UPDATE auth_email_verification_outbox
                SET status = 'CANCELLED',
                    encrypted_token = NULL,
                    lease_token = NULL,
                    lease_until = NULL
                WHERE user_id = OLD.id
                  AND status IN ('PENDING', 'PROCESSING');
            END IF;

            RETURN NEW;
        END;
        $function$
    $ddl$, app_schema, app_schema);

    EXECUTE format($ddl$
        CREATE FUNCTION %I.cancel_email_verification_delivery()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        SET search_path = pg_catalog, %I, pg_temp
        AS $function$
        BEGIN
            IF (NEW.consumed_at IS DISTINCT FROM OLD.consumed_at
                    AND NEW.consumed_at IS NOT NULL)
                OR (NEW.revoked_at IS DISTINCT FROM OLD.revoked_at
                    AND NEW.revoked_at IS NOT NULL) THEN
                UPDATE auth_email_verification_outbox
                SET status = 'CANCELLED',
                    encrypted_token = NULL,
                    lease_token = NULL,
                    lease_until = NULL
                WHERE token_id = NEW.id
                  AND status IN ('PENDING', 'PROCESSING');
            END IF;
            RETURN NEW;
        END;
        $function$
    $ddl$, app_schema, app_schema);

    EXECUTE format(
        'CREATE TRIGGER tr_auth_email_verification_cancel_delivery '
        'AFTER UPDATE OF consumed_at, revoked_at ON %I.auth_email_verification_tokens '
        'FOR EACH ROW EXECUTE FUNCTION %I.cancel_email_verification_delivery()',
        app_schema, app_schema);

    EXECUTE format(
        'REVOKE ALL ON FUNCTION %I.cancel_email_verification_delivery() FROM PUBLIC',
        app_schema);
END;
$replace_email_change_trigger$;

DO $outbox_privileges$
DECLARE
    app_schema TEXT := current_schema();
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cobalto_app') THEN
        EXECUTE format(
            'GRANT INSERT (recipient_email) ON TABLE %I.auth_email_verification_outbox '
            'TO cobalto_app',
            app_schema);
        EXECUTE format(
            'GRANT UPDATE (lease_token, lease_until) '
            'ON TABLE %I.auth_email_verification_outbox TO cobalto_app',
            app_schema);
    END IF;
END;
$outbox_privileges$;
