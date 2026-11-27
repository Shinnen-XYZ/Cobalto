DO $email_change_trigger$
DECLARE
    app_schema TEXT := current_schema();
BEGIN
    EXECUTE format($ddl$
        CREATE FUNCTION %I.invalidate_email_verification_on_email_change()
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
                    encrypted_token = NULL
                WHERE user_id = OLD.id
                  AND status = 'PENDING';
            END IF;

            RETURN NEW;
        END;
        $function$
    $ddl$, app_schema, app_schema);

    EXECUTE format(
        'CREATE TRIGGER tr_app_users_email_change_invalidate '
        'BEFORE UPDATE OF email ON %I.app_users '
        'FOR EACH ROW EXECUTE FUNCTION %I.invalidate_email_verification_on_email_change()',
        app_schema, app_schema);

    EXECUTE format(
        'REVOKE ALL ON FUNCTION %I.invalidate_email_verification_on_email_change() FROM PUBLIC',
        app_schema);
END;
$email_change_trigger$;
