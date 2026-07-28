CREATE TABLE project_api_keys
(
    id          UUID         NOT NULL,
    tenant_id   UUID         NOT NULL,
    project_id  UUID         NOT NULL,

    name        VARCHAR(100) NOT NULL,
    key_id      VARCHAR(32)  NOT NULL,
    secret_hash BYTEA        NOT NULL,

    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at  TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,

    CONSTRAINT pk_project_api_keys
        PRIMARY KEY (id),

    CONSTRAINT uq_project_api_keys_key_id
        UNIQUE (key_id),

    CONSTRAINT fk_project_api_keys_project
        FOREIGN KEY (tenant_id, project_id)
            REFERENCES projects (tenant_id, id)
            ON DELETE CASCADE,

    CONSTRAINT chk_project_api_keys_name_not_blank
        CHECK (BTRIM(name) <> ''),

    CONSTRAINT chk_project_api_keys_key_id_not_blank
        CHECK (BTRIM(key_id) <> ''),

    CONSTRAINT chk_project_api_keys_secret_hash_length
        CHECK (OCTET_LENGTH(secret_hash) = 32),

    CONSTRAINT chk_project_api_keys_expires_at
        CHECK (
            expires_at IS NULL
                OR expires_at > created_at
            ),

    CONSTRAINT chk_project_api_keys_revoked_at
        CHECK (
            revoked_at IS NULL
                OR revoked_at >= created_at
            )
);

CREATE INDEX idx_project_api_keys_project
    ON project_api_keys (
                         tenant_id,
                         project_id,
                         created_at DESC
        );