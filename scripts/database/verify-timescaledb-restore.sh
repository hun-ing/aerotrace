#!/usr/bin/env bash

set -Eeuo pipefail

container_name=""
database_name=""

usage() {
    cat <<'EOF'
Usage:
  verify-timescaledb-restore.sh \
    --container <running-timescaledb-container> \
    --database <database-name>

Prints only aggregate counts, schema state, versions, and a one-way application
data fingerprint. It does not print API Key hashes, trace payloads, or row IDs.
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 ||
        fail "Required command was not found: $1"
}

while (( $# > 0 )); do
    case "$1" in
        --container)
            (( $# >= 2 )) || fail "--container requires a value."
            container_name="$2"
            shift 2
            ;;
        --database)
            (( $# >= 2 )) || fail "--database requires a value."
            database_name="$2"
            shift 2
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            fail "Unknown argument: $1"
            ;;
    esac
done

[[ -n "${container_name}" ]] || fail "--container is required."
[[ -n "${database_name}" ]] || fail "--database is required."
[[ "${container_name}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] ||
    fail "Container name contains unsupported characters."
[[ "${database_name}" =~ ^[a-z][a-z0-9_]*$ ]] ||
    fail "Database name must match: ^[a-z][a-z0-9_]*$"

require_command docker

container_state="$(
    docker inspect \
        --format '{{.State.Status}}' \
        "${container_name}" \
        2>/dev/null ||
        true
)"

[[ "${container_state}" == "running" ]] ||
    fail "TimescaleDB container is not running: ${container_name}"

summary="$(
    docker exec -i "${container_name}" sh -c \
        'exec psql \
            -U "$POSTGRES_USER" \
            -d "$1" \
            -At \
            -F "|" \
            -v ON_ERROR_STOP=1' \
        sh \
        "${database_name}" <<'SQL'
WITH application_tables AS (
    SELECT COUNT(*) AS table_count
    FROM (VALUES
        (to_regclass('public.tenants')),
        (to_regclass('public.projects')),
        (to_regclass('public.project_api_keys')),
        (to_regclass('public.spans'))
    ) AS required(table_oid)
    WHERE table_oid IS NOT NULL
),
hypertable_state AS (
    SELECT
        COUNT(*) AS hypertable_count,
        COUNT(*) FILTER (WHERE compression_enabled) AS compression_enabled_count
    FROM timescaledb_information.hypertables
    WHERE hypertable_schema = 'public'
      AND hypertable_name = 'spans'
),
policy_state AS (
    SELECT COUNT(DISTINCT proc_name) AS scheduled_policy_count
    FROM timescaledb_information.jobs
    WHERE hypertable_schema = 'public'
      AND hypertable_name = 'spans'
      AND proc_name IN ('policy_compression', 'policy_retention')
      AND scheduled
)
SELECT
    current_setting('server_version_num'),
    (SELECT extversion FROM pg_extension WHERE extname = 'timescaledb'),
    (SELECT table_count FROM application_tables),
    (SELECT hypertable_count FROM hypertable_state),
    (SELECT compression_enabled_count FROM hypertable_state),
    (SELECT scheduled_policy_count FROM policy_state)
;
SQL
)"

IFS='|' read -r \
    postgres_server_version_num \
    timescaledb_version \
    required_table_count \
    spans_hypertable_count \
    spans_compression_enabled_count \
    spans_scheduled_policy_count \
    <<<"${summary}"

[[ "${required_table_count}" == "4" ]] ||
    fail "Expected four AeroTrace application tables; found ${required_table_count}."
[[ "${spans_hypertable_count}" == "1" ]] ||
    fail "public.spans is not a TimescaleDB hypertable."
[[ "${spans_compression_enabled_count}" == "1" ]] ||
    fail "public.spans columnstore/compression is not enabled."
[[ "${spans_scheduled_policy_count}" == "2" ]] ||
    fail \
        "Expected scheduled columnstore and retention policies; " \
        "found ${spans_scheduled_policy_count}."

data_summary="$(
    docker exec -i "${container_name}" sh -c \
        'exec psql \
            -U "$POSTGRES_USER" \
            -d "$1" \
            -At \
            -F "|" \
            -v ON_ERROR_STOP=1' \
        sh \
        "${database_name}" <<'SQL'
SELECT
    (SELECT COUNT(*) FROM public.tenants),
    (SELECT COUNT(*) FROM public.projects),
    (SELECT COUNT(*) FROM public.project_api_keys),
    (SELECT COUNT(*) FROM public.spans),
    md5(
        COALESCE(
            (SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::TEXT FROM public.tenants t),
            '[]'
        ) ||
        COALESCE(
            (SELECT jsonb_agg(to_jsonb(p) ORDER BY id)::TEXT FROM public.projects p),
            '[]'
        ) ||
        COALESCE(
            (
                SELECT jsonb_agg(to_jsonb(k) ORDER BY id)::TEXT
                FROM public.project_api_keys k
            ),
            '[]'
        ) ||
        COALESCE(
            (
                SELECT jsonb_agg(
                    to_jsonb(s)
                    ORDER BY
                        tenant_id,
                        project_id,
                        trace_id,
                        span_id,
                        start_time
                )::TEXT
                FROM public.spans s
            ),
            '[]'
        )
    )
;
SQL
)"

IFS='|' read -r \
    tenant_count \
    project_count \
    project_api_key_count \
    span_count \
    application_data_fingerprint \
    <<<"${data_summary}"

echo "verification_result=PASS"
echo "postgres_server_version_num=${postgres_server_version_num}"
echo "timescaledb_version=${timescaledb_version}"
echo "required_table_count=${required_table_count}"
echo "spans_hypertable_count=${spans_hypertable_count}"
echo "spans_compression_enabled_count=${spans_compression_enabled_count}"
echo "spans_scheduled_policy_count=${spans_scheduled_policy_count}"
echo "tenant_count=${tenant_count}"
echo "project_count=${project_count}"
echo "project_api_key_count=${project_api_key_count}"
echo "span_count=${span_count}"
echo "application_data_fingerprint=${application_data_fingerprint}"
