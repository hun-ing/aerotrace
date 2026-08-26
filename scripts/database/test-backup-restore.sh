#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(
    cd "$(dirname "${BASH_SOURCE[0]}")/../.."
    pwd
)"

timescaledb_image="timescale/timescaledb:2.28.3-pg15"
run_token="$$-$(date +%s)"
source_container="aerotrace-backup-restore-source-${run_token}"
target_container="aerotrace-backup-restore-target-${run_token}"
test_label="aerotrace.test-scope=backup-restore"
work_dir="$(
    mktemp -d \
        "${TMPDIR:-/tmp}/aerotrace-backup-restore-test.XXXXXX"
)"

cleanup_container() {
    local container_name="$1"
    local label_value

    if ! docker inspect "${container_name}" >/dev/null 2>&1; then
        return
    fi

    label_value="$(
        docker inspect \
            --format '{{index .Config.Labels "aerotrace.test-scope"}}' \
            "${container_name}"
    )"

    if [[ "${label_value}" != "backup-restore" ]]; then
        echo \
            "Refusing to remove container without acceptance label: " \
            "${container_name}" \
            >&2
        return
    fi

    docker rm -f "${container_name}" >/dev/null
}

cleanup() {
    cleanup_container "${source_container}"
    cleanup_container "${target_container}"

    case "${work_dir}" in
        "${TMPDIR:-/tmp}"/aerotrace-backup-restore-test.*)
            rm -rf -- "${work_dir}"
            ;;
        *)
            echo "Refusing to remove unexpected work directory: ${work_dir}" >&2
            ;;
    esac
}

trap cleanup EXIT

wait_for_database() {
    local container_name="$1"
    local deadline="$(( $(date +%s) + 120 ))"

    while (( $(date +%s) < deadline )); do
        if docker exec "${container_name}" sh -c '
            exec pg_isready \
                -U "$POSTGRES_USER" \
                -d "$POSTGRES_DB"
        ' >/dev/null 2>&1; then
            return
        fi

        sleep 2
    done

    echo "Timed out waiting for database: ${container_name}" >&2
    docker logs "${container_name}" >&2 || true
    exit 1
}

start_database() {
    local container_name="$1"
    local database_name="$2"

    docker run \
        --detach \
        --name "${container_name}" \
        --label "${test_label}" \
        --network none \
        --tmpfs /var/lib/postgresql/data:rw,nosuid,nodev,size=512m \
        --env POSTGRES_USER=aerotrace_test \
        --env POSTGRES_PASSWORD=aerotrace-ci-only \
        --env "POSTGRES_DB=${database_name}" \
        "${timescaledb_image}" \
        >/dev/null

    wait_for_database "${container_name}"
}

command -v docker >/dev/null 2>&1

start_database "${source_container}" aerotrace_source

migration_dir="${repository_root}/backend/src/main/resources/db/migration"
mapfile -t migrations < <(
    printf '%s\n' "${migration_dir}"/V*.sql | sort -V
)

for migration in "${migrations[@]}"; do
    docker exec -i "${source_container}" sh -c '
        exec psql \
            -U "$POSTGRES_USER" \
            -d "$POSTGRES_DB" \
            -v ON_ERROR_STOP=1
    ' <"${migration}" >/dev/null
done

docker exec -i "${source_container}" sh -c '
    exec psql \
        -U "$POSTGRES_USER" \
        -d "$POSTGRES_DB" \
        -v ON_ERROR_STOP=1
' <"${repository_root}/tests/fixtures/database-backup-restore-fixture.sql" \
    >/dev/null

source_summary="${work_dir}/source.summary"
target_summary="${work_dir}/target.summary"

bash "${repository_root}/scripts/database/verify-timescaledb-restore.sh" \
    --container "${source_container}" \
    --database aerotrace_source \
    >"${source_summary}"

backup_output="$(
    bash "${repository_root}/scripts/database/backup-timescaledb.sh" \
        --container "${source_container}" \
        --output-dir "${work_dir}"
)"

archive_path="$(
    awk -F= '$1 == "archive_path" {print substr($0, index($0, "=") + 1)}' \
        <<<"${backup_output}"
)"
metadata_path="$(
    awk -F= '$1 == "metadata_path" {print substr($0, index($0, "=") + 1)}' \
        <<<"${backup_output}"
)"
checksum_path="$(
    awk -F= '$1 == "checksum_path" {print substr($0, index($0, "=") + 1)}' \
        <<<"${backup_output}"
)"

[[ -f "${archive_path}" ]]
[[ -f "${metadata_path}" ]]
[[ -f "${checksum_path}" ]]
[[ "$(stat -c '%a' "${archive_path}")" == "600" ]]
[[ "$(stat -c '%a' "${metadata_path}")" == "600" ]]
[[ "$(stat -c '%a' "${checksum_path}")" == "600" ]]

start_database "${target_container}" bootstrap

bash "${repository_root}/scripts/database/restore-timescaledb.sh" \
    --container "${target_container}" \
    --target-database aerotrace_restored \
    --archive "${archive_path}" \
    --metadata "${metadata_path}" \
    --checksum "${checksum_path}" \
    --confirm-create-empty-target \
    >/dev/null

bash "${repository_root}/scripts/database/verify-timescaledb-restore.sh" \
    --container "${target_container}" \
    --database aerotrace_restored \
    >"${target_summary}"

if ! cmp -s "${source_summary}" "${target_summary}"; then
    echo "Source and restored verification summaries differ:" >&2
    diff -u "${source_summary}" "${target_summary}" >&2 || true
    exit 1
fi

existing_error="${work_dir}/existing-target.error"
if bash "${repository_root}/scripts/database/restore-timescaledb.sh" \
    --container "${target_container}" \
    --target-database aerotrace_restored \
    --archive "${archive_path}" \
    --metadata "${metadata_path}" \
    --checksum "${checksum_path}" \
    --confirm-create-empty-target \
    >/dev/null \
    2>"${existing_error}"; then
    echo "Restore unexpectedly overwrote an existing database." >&2
    exit 1
fi

grep -Fq \
    'Target database already exists; overwrite is forbidden' \
    "${existing_error}"

production_target_error="${work_dir}/production-target.error"
if bash "${repository_root}/scripts/database/restore-timescaledb.sh" \
    --container aerotrace-timescaledb \
    --target-database forbidden_production_target \
    --archive "${archive_path}" \
    --metadata "${metadata_path}" \
    --checksum "${checksum_path}" \
    --confirm-create-empty-target \
    >/dev/null \
    2>"${production_target_error}"; then
    echo "Restore unexpectedly accepted the production baseline container." >&2
    exit 1
fi

grep -Fq \
    'Refusing to restore into the production baseline container' \
    "${production_target_error}"

corrupt_dir="${work_dir}/corrupt"
mkdir -m 0700 "${corrupt_dir}"
corrupt_archive="${corrupt_dir}/$(basename "${archive_path}")"
cp "${archive_path}" "${corrupt_archive}"
printf 'corruption-marker' >>"${corrupt_archive}"

checksum_error="${work_dir}/checksum.error"
if bash "${repository_root}/scripts/database/restore-timescaledb.sh" \
    --container "${target_container}" \
    --target-database checksum_failure_target \
    --archive "${corrupt_archive}" \
    --metadata "${metadata_path}" \
    --checksum "${checksum_path}" \
    --confirm-create-empty-target \
    >/dev/null \
    2>"${checksum_error}"; then
    echo "Restore unexpectedly accepted a corrupted archive." >&2
    exit 1
fi

grep -Fq 'Archive checksum verification failed' "${checksum_error}"

checksum_target_exists="$(
    docker exec "${target_container}" sh -c '
        exec psql \
            -U "$POSTGRES_USER" \
            -d postgres \
            -At \
            -c "
                SELECT 1
                FROM pg_database
                WHERE datname = '\''checksum_failure_target'\''
            "
    '
)"

[[ -z "${checksum_target_exists}" ]]

version_metadata="${work_dir}/version-mismatch.metadata"
sed \
    's/^timescaledb_version=.*/timescaledb_version=0.0.0/' \
    "${metadata_path}" \
    >"${version_metadata}"
chmod 0600 "${version_metadata}"

version_error="${work_dir}/version.error"
if bash "${repository_root}/scripts/database/restore-timescaledb.sh" \
    --container "${target_container}" \
    --target-database version_mismatch_target \
    --archive "${archive_path}" \
    --metadata "${version_metadata}" \
    --checksum "${checksum_path}" \
    --confirm-create-empty-target \
    >/dev/null \
    2>"${version_error}"; then
    echo "Restore unexpectedly accepted a TimescaleDB version mismatch." >&2
    exit 1
fi

grep -Fq 'TimescaleDB version mismatch' "${version_error}"

version_target_exists="$(
    docker exec "${target_container}" sh -c '
        exec psql \
            -U "$POSTGRES_USER" \
            -d postgres \
            -At \
            -c "
                SELECT 1
                FROM pg_database
                WHERE datname = '\''version_mismatch_target'\''
            "
    '
)"

[[ -z "${version_target_exists}" ]]

if grep -Eq \
    'aerotrace-ci-only|atr_[A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{43}|ati_[A-Za-z0-9_-]{43}' \
    "${metadata_path}" \
    "${checksum_path}"; then
    echo "Backup metadata or checksum exposed a credential." >&2
    exit 1
fi

echo "backup_restore_acceptance=PASS"
echo "source_target_summary_match=yes"
echo "existing_target_overwrite_refused=yes"
echo "production_target_container_refused=yes"
echo "invalid_archive_refused_before_target_creation=yes"
echo "version_mismatch_refused_before_target_creation=yes"
echo "metadata_credential_scan=PASS"
