CREATE TABLE tenants
(
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    name VARCHAR(100) NOT NULL,
    slug VARCHAR(100) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_tenants_slug
        UNIQUE (slug),

    CONSTRAINT ck_tenants_name_not_blank
        CHECK (btrim(name) <> ''),

    CONSTRAINT ck_tenants_slug_format
        CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$')
);

CREATE TABLE projects
(
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    tenant_id UUID NOT NULL,

    name VARCHAR(100) NOT NULL,
    slug VARCHAR(100) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_projects_tenant
        FOREIGN KEY (tenant_id)
            REFERENCES tenants (id)
            ON DELETE RESTRICT,

    CONSTRAINT uq_projects_tenant_slug
        UNIQUE (tenant_id, slug),

    CONSTRAINT ck_projects_name_not_blank
        CHECK (btrim(name) <> ''),

    CONSTRAINT ck_projects_slug_format
        CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$')
);