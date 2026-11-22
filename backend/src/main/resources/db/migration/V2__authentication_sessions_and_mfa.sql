ALTER TABLE app_users
    ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN mfa_secret_encrypted BYTEA,
    ADD COLUMN mfa_pending_secret_encrypted BYTEA,
    ADD COLUMN mfa_pending_expires_at TIMESTAMPTZ,
    ADD COLUMN mfa_last_counter BIGINT NOT NULL DEFAULT -1,
    ADD COLUMN mfa_failed_attempts SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN mfa_locked_until TIMESTAMPTZ,
    ADD CONSTRAINT ck_app_users_mfa_secret
        CHECK (NOT mfa_enabled OR mfa_secret_encrypted IS NOT NULL),
    ADD CONSTRAINT ck_app_users_mfa_pending
        CHECK (
            (mfa_pending_secret_encrypted IS NULL AND mfa_pending_expires_at IS NULL)
            OR (mfa_pending_secret_encrypted IS NOT NULL AND mfa_pending_expires_at IS NOT NULL)
        ),
    ADD CONSTRAINT ck_app_users_mfa_attempts
        CHECK (mfa_failed_attempts BETWEEN 0 AND 5);

CREATE TABLE auth_refresh_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    family_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by UUID,
    CONSTRAINT ck_auth_refresh_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_auth_refresh_sessions_user ON auth_refresh_sessions(user_id);
CREATE INDEX ix_auth_refresh_sessions_family ON auth_refresh_sessions(family_id);
CREATE INDEX ix_auth_refresh_sessions_expiry ON auth_refresh_sessions(expires_at);

CREATE TABLE auth_mfa_login_challenges (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    failed_attempts SMALLINT NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT ck_auth_mfa_challenge_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_auth_mfa_challenge_attempts CHECK (failed_attempts BETWEEN 0 AND 5)
);

CREATE INDEX ix_auth_mfa_challenges_expiry ON auth_mfa_login_challenges(expires_at);

CREATE TABLE auth_mfa_recovery_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    code_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    CONSTRAINT uq_auth_mfa_recovery_user_hash UNIQUE (user_id, code_hash)
);
