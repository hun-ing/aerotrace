ALTER TABLE projects
    ADD CONSTRAINT uq_projects_tenant_id_id
        UNIQUE (tenant_id, id);

CREATE TABLE spans
(
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,

    trace_id VARCHAR(32) NOT NULL,
    span_id VARCHAR(16) NOT NULL,
    parent_span_id VARCHAR(16),

    trace_state TEXT NOT NULL DEFAULT '',
    flags BIGINT NOT NULL DEFAULT 0,

    service_name VARCHAR(255) NOT NULL,

    scope_name VARCHAR(255) NOT NULL DEFAULT '',
    scope_version VARCHAR(100) NOT NULL DEFAULT '',

    name VARCHAR(255) NOT NULL,
    span_kind SMALLINT NOT NULL,

    status_code SMALLINT NOT NULL DEFAULT 0,
    status_message TEXT NOT NULL DEFAULT '',

    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    duration_nano BIGINT NOT NULL,

    resource_attributes JSONB NOT NULL DEFAULT '{}'::JSONB,
    span_attributes JSONB NOT NULL DEFAULT '{}'::JSONB,
    events JSONB NOT NULL DEFAULT '[]'::JSONB,
    links JSONB NOT NULL DEFAULT '[]'::JSONB,

    ingested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_spans_project
        FOREIGN KEY (tenant_id, project_id)
            REFERENCES projects (tenant_id, id)
            ON DELETE RESTRICT,

    CONSTRAINT ck_spans_trace_id_format
        CHECK (
            trace_id ~ '^[0-9a-f]{32}$'
                AND trace_id <> repeat('0', 32)
            ),

    CONSTRAINT ck_spans_span_id_format
        CHECK (
            span_id ~ '^[0-9a-f]{16}$'
                AND span_id <> repeat('0', 16)
            ),

    CONSTRAINT ck_spans_parent_span_id_format
        CHECK (
            parent_span_id IS NULL
                OR (
                parent_span_id ~ '^[0-9a-f]{16}$'
                    AND parent_span_id <> repeat('0', 16)
                )
            ),

    CONSTRAINT ck_spans_flags_range
        CHECK (flags BETWEEN 0 AND 4294967295),

    CONSTRAINT ck_spans_service_name_not_blank
        CHECK (btrim(service_name) <> ''),

    CONSTRAINT ck_spans_name_not_blank
        CHECK (btrim(name) <> ''),

    CONSTRAINT ck_spans_span_kind_range
        CHECK (span_kind BETWEEN 0 AND 5),

    CONSTRAINT ck_spans_status_code_range
        CHECK (status_code BETWEEN 0 AND 2),

    CONSTRAINT ck_spans_time_order
        CHECK (end_time >= start_time),

    CONSTRAINT ck_spans_duration_non_negative
        CHECK (duration_nano >= 0),

    CONSTRAINT ck_spans_resource_attributes_object
        CHECK (jsonb_typeof(resource_attributes) = 'object'),

    CONSTRAINT ck_spans_span_attributes_object
        CHECK (jsonb_typeof(span_attributes) = 'object'),

    CONSTRAINT ck_spans_events_array
        CHECK (jsonb_typeof(events) = 'array'),

    CONSTRAINT ck_spans_links_array
        CHECK (jsonb_typeof(links) = 'array')
);

SELECT create_hypertable(
               'spans',
               by_range('start_time', INTERVAL '1 day'),
               if_not_exists => TRUE
       );

CREATE UNIQUE INDEX ux_spans_identity
    ON spans (
              tenant_id,
              project_id,
              trace_id,
              span_id,
              start_time
        );

CREATE INDEX ix_spans_recent
    ON spans (
              tenant_id,
              project_id,
              start_time DESC
        );

CREATE INDEX ix_spans_trace_lookup
    ON spans (
              tenant_id,
              project_id,
              trace_id,
              start_time ASC
        );