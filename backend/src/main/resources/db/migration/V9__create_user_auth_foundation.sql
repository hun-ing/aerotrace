CREATE TABLE app_users
(
    id            UUID         NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    avatar_url    TEXT,
    status        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMPTZ,

    CONSTRAINT pk_app_users
        PRIMARY KEY (id),

    CONSTRAINT chk_app_users_display_name_not_blank
        CHECK (BTRIM(display_name) <> ''),

    CONSTRAINT chk_app_users_status
        CHECK (status IN ('ACTIVE', 'DISABLED')),

    CONSTRAINT chk_app_users_updated_at
        CHECK (updated_at >= created_at),

    CONSTRAINT chk_app_users_last_login_at
        CHECK (
            last_login_at IS NULL
                OR last_login_at >= created_at
        )
);

CREATE TABLE user_identities
(
    id               UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    provider         VARCHAR(20)  NOT NULL,
    provider_subject VARCHAR(100) NOT NULL,
    provider_login   VARCHAR(100) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_user_identities
        PRIMARY KEY (id),

    CONSTRAINT fk_user_identities_user
        FOREIGN KEY (user_id)
            REFERENCES app_users (id)
            ON DELETE CASCADE,

    CONSTRAINT uq_user_identities_provider_subject
        UNIQUE (provider, provider_subject),

    CONSTRAINT uq_user_identities_user_provider
        UNIQUE (user_id, provider),

    CONSTRAINT chk_user_identities_provider
        CHECK (provider = 'GITHUB'),

    CONSTRAINT chk_user_identities_subject_not_blank
        CHECK (BTRIM(provider_subject) <> ''),

    CONSTRAINT chk_user_identities_login_not_blank
        CHECK (BTRIM(provider_login) <> ''),

    CONSTRAINT chk_user_identities_last_seen_at
        CHECK (last_seen_at >= created_at)
);

CREATE TABLE tenant_memberships
(
    tenant_id UUID        NOT NULL,
    user_id   UUID        NOT NULL,
    role      VARCHAR(20) NOT NULL,
    status    VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,

    CONSTRAINT pk_tenant_memberships
        PRIMARY KEY (tenant_id, user_id),

    CONSTRAINT fk_tenant_memberships_tenant
        FOREIGN KEY (tenant_id)
            REFERENCES tenants (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_tenant_memberships_user
        FOREIGN KEY (user_id)
            REFERENCES app_users (id)
            ON DELETE CASCADE,

    CONSTRAINT chk_tenant_memberships_role
        CHECK (role IN ('OWNER', 'ADMIN', 'VIEWER')),

    CONSTRAINT chk_tenant_memberships_status
        CHECK (status IN ('ACTIVE', 'REVOKED')),

    CONSTRAINT chk_tenant_memberships_updated_at
        CHECK (updated_at >= created_at),

    CONSTRAINT chk_tenant_memberships_revocation_state
        CHECK (
            (status = 'ACTIVE' AND revoked_at IS NULL)
                OR
            (
                status = 'REVOKED'
                AND revoked_at IS NOT NULL
                AND revoked_at >= created_at
            )
        )
);

CREATE INDEX idx_tenant_memberships_user_active
    ON tenant_memberships (user_id, tenant_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_tenant_memberships_tenant_active_role
    ON tenant_memberships (tenant_id, role, user_id)
    WHERE status = 'ACTIVE';

CREATE TABLE onboarding_invites
(
    id                   UUID        NOT NULL,
    tenant_id            UUID        NOT NULL,
    role                 VARCHAR(20) NOT NULL,
    token_hash           BYTEA       NOT NULL,
    created_by_user_id   UUID,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at           TIMESTAMPTZ NOT NULL,
    consumed_at          TIMESTAMPTZ,
    consumed_by_user_id  UUID,
    revoked_at           TIMESTAMPTZ,

    CONSTRAINT pk_onboarding_invites
        PRIMARY KEY (id),

    CONSTRAINT fk_onboarding_invites_tenant
        FOREIGN KEY (tenant_id)
            REFERENCES tenants (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_onboarding_invites_creator
        FOREIGN KEY (created_by_user_id)
            REFERENCES app_users (id)
            ON DELETE RESTRICT,

    CONSTRAINT fk_onboarding_invites_consumer
        FOREIGN KEY (consumed_by_user_id)
            REFERENCES app_users (id)
            ON DELETE RESTRICT,

    CONSTRAINT uq_onboarding_invites_token_hash
        UNIQUE (token_hash),

    CONSTRAINT chk_onboarding_invites_role
        CHECK (role IN ('OWNER', 'ADMIN', 'VIEWER')),

    CONSTRAINT chk_onboarding_invites_operator_role
        CHECK (
            created_by_user_id IS NOT NULL
                OR role = 'OWNER'
        ),

    CONSTRAINT chk_onboarding_invites_token_hash_length
        CHECK (OCTET_LENGTH(token_hash) = 32),

    CONSTRAINT chk_onboarding_invites_expiration
        CHECK (expires_at > created_at),

    CONSTRAINT chk_onboarding_invites_consumption_state
        CHECK (
            (consumed_at IS NULL AND consumed_by_user_id IS NULL)
                OR
            (
                consumed_at IS NOT NULL
                AND consumed_by_user_id IS NOT NULL
                AND consumed_at >= created_at
            )
        ),

    CONSTRAINT chk_onboarding_invites_revoked_at
        CHECK (
            revoked_at IS NULL
                OR revoked_at >= created_at
        ),

    CONSTRAINT chk_onboarding_invites_terminal_state
        CHECK (
            consumed_at IS NULL
                OR revoked_at IS NULL
        )
);

CREATE INDEX idx_onboarding_invites_tenant_open
    ON onboarding_invites (tenant_id, expires_at, id)
    WHERE consumed_at IS NULL
      AND revoked_at IS NULL;

CREATE TABLE security_audit_events
(
    id             UUID         NOT NULL,
    actor_user_id  UUID,
    tenant_id      UUID,
    project_id     UUID,
    action         VARCHAR(100) NOT NULL,
    result         VARCHAR(20)  NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID         NOT NULL,

    CONSTRAINT pk_security_audit_events
        PRIMARY KEY (id),

    CONSTRAINT fk_security_audit_events_actor
        FOREIGN KEY (actor_user_id)
            REFERENCES app_users (id)
            ON DELETE SET NULL,

    CONSTRAINT fk_security_audit_events_tenant
        FOREIGN KEY (tenant_id)
            REFERENCES tenants (id)
            ON DELETE SET NULL,

    CONSTRAINT chk_security_audit_events_action_not_blank
        CHECK (BTRIM(action) <> ''),

    CONSTRAINT chk_security_audit_events_result
        CHECK (result IN ('SUCCESS', 'DENIED', 'FAILURE'))
);

CREATE INDEX idx_security_audit_events_tenant_time
    ON security_audit_events (tenant_id, occurred_at DESC, id);

CREATE INDEX idx_security_audit_events_actor_time
    ON security_audit_events (actor_user_id, occurred_at DESC, id);

-- Spring Session JDBC PostgreSQL schema with the AeroTrace table prefix.
-- Runtime schema initialization remains disabled; Flyway owns this schema.
CREATE TABLE AEROTRACE_SESSION
(
    PRIMARY_ID            CHAR(36)     NOT NULL,
    SESSION_ID            CHAR(36)     NOT NULL,
    CREATION_TIME         BIGINT       NOT NULL,
    LAST_ACCESS_TIME      BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL INTEGER      NOT NULL,
    EXPIRY_TIME           BIGINT       NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),

    CONSTRAINT AEROTRACE_SESSION_PK
        PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX AEROTRACE_SESSION_IX1
    ON AEROTRACE_SESSION (SESSION_ID);

CREATE INDEX AEROTRACE_SESSION_IX2
    ON AEROTRACE_SESSION (EXPIRY_TIME);

CREATE INDEX AEROTRACE_SESSION_IX3
    ON AEROTRACE_SESSION (PRINCIPAL_NAME);

CREATE TABLE AEROTRACE_SESSION_ATTRIBUTES
(
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,

    CONSTRAINT AEROTRACE_SESSION_ATTRIBUTES_PK
        PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),

    CONSTRAINT AEROTRACE_SESSION_ATTRIBUTES_FK
        FOREIGN KEY (SESSION_PRIMARY_ID)
            REFERENCES AEROTRACE_SESSION (PRIMARY_ID)
            ON DELETE CASCADE
);

CREATE INDEX AEROTRACE_SESSION_ATTRIBUTES_IX1
    ON AEROTRACE_SESSION_ATTRIBUTES (SESSION_PRIMARY_ID);
