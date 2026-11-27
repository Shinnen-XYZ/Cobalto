UPDATE auth_email_verification_outbox
SET attempts = LEAST(attempts + 1, 5)
WHERE status = 'PROCESSING';

DO $replace_confirmation_function$
DECLARE
    app_schema TEXT := current_schema();
BEGIN
    EXECUTE format($ddl$
        CREATE OR REPLACE FUNCTION %I.consume_email_verification_token(p_token_hash VARCHAR)
        RETURNS UUID
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, %I, pg_temp
        AS $function$
        DECLARE
            verified_user_id UUID;
            verification_token_id UUID;
        BEGIN
            IF p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$' THEN
                RETURN NULL;
            END IF;

            SELECT user_id INTO verified_user_id
            FROM auth_email_verification_tokens
            WHERE token_hash = p_token_hash
              AND consumed_at IS NULL
              AND revoked_at IS NULL
              AND expires_at > clock_timestamp();

            IF NOT FOUND THEN
                RETURN NULL;
            END IF;

            PERFORM 1
            FROM app_users
            WHERE id = verified_user_id
              AND email_verified = FALSE
            FOR UPDATE;

            IF NOT FOUND THEN
                RETURN NULL;
            END IF;

            SELECT id INTO verification_token_id
            FROM auth_email_verification_tokens
            WHERE token_hash = p_token_hash
              AND user_id = verified_user_id
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
            WHERE id = verification_token_id
              AND consumed_at IS NULL
              AND revoked_at IS NULL;

            IF NOT FOUND THEN
                RETURN NULL;
            END IF;

            UPDATE auth_email_verification_tokens
            SET revoked_at = clock_timestamp()
            WHERE user_id = verified_user_id
              AND id <> verification_token_id
              AND consumed_at IS NULL
              AND revoked_at IS NULL;

            UPDATE auth_email_verification_outbox
            SET status = 'CANCELLED',
                encrypted_token = NULL,
                lease_token = NULL,
                lease_until = NULL
            WHERE user_id = verified_user_id
              AND status IN ('PENDING', 'PROCESSING');

            RETURN verified_user_id;
        END;
        $function$
    $ddl$, app_schema, app_schema);
END;
$replace_confirmation_function$;
