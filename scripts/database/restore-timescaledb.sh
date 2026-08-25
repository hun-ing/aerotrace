#!/usr/bin/env bash

set -Eeuo pipefail

container_name=""
target_database=""
archive_path=""
metadata_path=""
checksum_path=""
confirmed_empty_target=false

usage() {
    cat <<'EOF'
Usage:
  restore-timescaledb.sh \
    --container <running-timescaledb-container> \
    --target-database <new-database-name> \
    --archive <backup.dump> \
    [--metadata <backup.metadata>] \
    [--checksum <backup.sha256>] \
    --confirm-create-empty-target

The target database must not already exist. This tool never drops, cleans, or
overwrites an existing database. PostgreSQL major and TimescaleDB extension
versions must match the backup metadata exactly.
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

assert_private_regular_file() {
    local file_path="$1"
    local file_mode
    local file_mode_value

    [[ -f "${file_path}" ]] || fail "Backup file was not found: ${file_path}"
    [[ ! -L "${file_path}" ]] ||
        fail "Backup file must not be a symbolic link: ${file_path}"

    file_mode="$(stat -c '%a' "${file_path}")"
    file_mode_value="$(( 8#${file_mode} ))"

    if (( (file_mode_value & 077) != 0 )); then
        fail \
            "Backup file must not grant group/other permissions: " \
            "${file_path} mode=${file_mode}"
    fi
}

metadata_value() {
    local key="$1"

    awk -F= -v key="${key}" '
        $1 == key {
            print substr($0, index($0, "=") + 1)
            found = 1
            exit
        }
        END {
            if (!found) {
                exit 1
            }
        }
    ' "${metadata_path}"
}

while (( $# > 0 )); do
    case "$1" in
        --container)
            (( $# >= 2 )) || fail "--container requires a value."
            container_name="$2"
            shift 2
            ;;
        --target-database)
            (( $# >= 2 )) || fail "--target-database requires a value."
            target_database="$2"
            shift 2
            ;;
        --archive)
            (( $# >= 2 )) || fail "--archive requires a value."
            archive_path="$2"
            shift 2
            ;;
        --metadata)
            (( $# >= 2 )) || fail "--metadata requires a value."
            metadata_path="$2"
            shift 2
            ;;
        --checksum)
            (( $# >= 2 )) || fail "--checksum requires a value."
            checksum_path="$2"
            shift 2
            ;;
        --confirm-create-empty-target)
            confirmed_empty_target=true
            shift
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
[[ -n "${target_database}" ]] || fail "--target-database is required."
[[ -n "${archive_path}" ]] || fail "--archive is required."
[[ "${confirmed_empty_target}" == true ]] ||
    fail "--confirm-create-empty-target is required."

[[ "${archive_path}" == /* ]] || fail "--archive must be an absolute path."
[[ "${container_name}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] ||
    fail "Container name contains unsupported characters."
[[ "${target_database}" =~ ^[a-z][a-z0-9_]*$ ]] ||
    fail "Target database must match: ^[a-z][a-z0-9_]*$"

case "${target_database}" in
    postgres | template0 | template1)
        fail "Refusing to use a reserved target database: ${target_database}"
        ;;
esac

[[ "${container_name}" != "aerotrace-timescaledb" ]] ||
    fail \
        "Refusing to restore into the production baseline container. " \
        "Prepare an isolated target container."

for required_command in awk basename docker sha256sum stat; do
    require_command "${required_command}"
done

archive_base="${archive_path%.dump}"
metadata_path="${metadata_path:-${archive_base}.metadata}"
checksum_path="${checksum_path:-${archive_base}.sha256}"

[[ "${metadata_path}" == /* ]] || fail "--metadata must be an absolute path."
[[ "${checksum_path}" == /* ]] || fail "--checksum must be an absolute path."

assert_private_regular_file "${archive_path}"
assert_private_regular_file "${metadata_path}"
assert_private_regular_file "${checksum_path}"

container_state="$(
    docker inspect \
        --format '{{.State.Status}}' \
        "${container_name}" \
        2>/dev/null ||
        true
)"

[[ "${container_state}" == "running" ]] ||
    fail "TimescaleDB container is not running: ${container_name}"

metadata_format="$(metadata_value format)"
metadata_archive_name="$(metadata_value archive_name)"
metadata_archive_sha256="$(metadata_value archive_sha256)"
source_postgres_major="$(metadata_value postgres_major)"
source_timescaledb_version="$(metadata_value timescaledb_version)"
parallel_restore_allowed="$(metadata_value parallel_restore_allowed)"

[[ "${metadata_format}" == "aerotrace-timescaledb-logical-backup-v1" ]] ||
    fail "Unsupported backup metadata format: ${metadata_format}"
[[ "${metadata_archive_name}" == "$(basename "${archive_path}")" ]] ||
    fail "Archive filename does not match backup metadata."
[[ "${parallel_restore_allowed}" == "false" ]] ||
    fail "Backup metadata does not prohibit parallel TimescaleDB restore."
[[ "${source_postgres_major}" =~ ^[0-9]+$ ]] ||
    fail "Invalid PostgreSQL major in backup metadata."
[[ -n "${source_timescaledb_version}" ]] ||
    fail "TimescaleDB version is missing from backup metadata."

checksum_file_sha256="$(awk 'NR == 1 {print $1}' "${checksum_path}")"
actual_archive_sha256="$(sha256sum "${archive_path}" | awk '{print $1}')"

[[ "${metadata_archive_sha256}" =~ ^[0-9a-f]{64}$ ]] ||
    fail "Invalid SHA-256 in backup metadata."
[[ "${checksum_file_sha256}" =~ ^[0-9a-f]{64}$ ]] ||
    fail "Invalid SHA-256 in checksum file."
[[ "${metadata_archive_sha256}" == "${checksum_file_sha256}" ]] ||
    fail "Metadata and checksum file disagree."
[[ "${actual_archive_sha256}" == "${metadata_archive_sha256}" ]] ||
    fail "Archive checksum verification failed."

docker exec -i "${container_name}" \
    pg_restore --list \
    <"${archive_path}" \
    >/dev/null

target_info="$(
    docker exec "${container_name}" sh -c '
        test -n "${POSTGRES_USER:-}" &&
        test -n "${POSTGRES_DB:-}" &&
        exec psql \
            -U "$POSTGRES_USER" \
            -d "$POSTGRES_DB" \
            -At \
            -F "|" \
            -v ON_ERROR_STOP=1 \
            -c "
                SELECT
                    current_setting('\''server_version_num'\''),
                    (
                        SELECT default_version
                        FROM pg_available_extensions
                        WHERE name = '\''timescaledb'\''
                    )
            "
    '
)"

IFS='|' read -r \
    target_postgres_server_version_num \
    target_timescaledb_version \
    <<<"${target_info}"

[[ "${target_postgres_server_version_num}" =~ ^[0-9]+$ ]] ||
    fail "Could not determine target PostgreSQL version."
[[ -n "${target_timescaledb_version}" ]] ||
    fail "TimescaleDB extension is unavailable in the target container."

target_postgres_major="$(( target_postgres_server_version_num / 10000 ))"

[[ "${target_postgres_major}" == "${source_postgres_major}" ]] ||
    fail \
        "PostgreSQL major mismatch: " \
        "source=${source_postgres_major}, target=${target_postgres_major}"
[[ "${target_timescaledb_version}" == "${source_timescaledb_version}" ]] ||
    fail \
        "TimescaleDB version mismatch: " \
        "source=${source_timescaledb_version}, target=${target_timescaledb_version}"

target_exists="$(
    printf '%s\n' \
        "SELECT 1 FROM pg_database WHERE datname = :'target_database';" |
    docker exec -i "${container_name}" sh -c \
        'exec psql \
            -U "$POSTGRES_USER" \
            -d postgres \
            -At \
            -v ON_ERROR_STOP=1 \
            -v "target_database=$1"' \
        sh \
        "${target_database}"
)"

[[ -z "${target_exists}" ]] ||
    fail "Target database already exists; overwrite is forbidden: ${target_database}"

docker exec "${container_name}" sh -c \
    'exec psql \
        -U "$POSTGRES_USER" \
        -d postgres \
        -v ON_ERROR_STOP=1 \
        -c "CREATE DATABASE \"$1\" WITH TEMPLATE template0"' \
    sh \
    "${target_database}"

restore_mode_enabled=false

leave_restore_mode() {
    if [[ "${restore_mode_enabled}" == true ]]; then
        docker exec "${container_name}" sh -c \
            'exec psql \
                -U "$POSTGRES_USER" \
                -d "$1" \
                -v ON_ERROR_STOP=1 \
                -c "SELECT timescaledb_post_restore()"' \
            sh \
            "${target_database}" \
            >/dev/null ||
            true
    fi
}

trap leave_restore_mode EXIT

docker exec "${container_name}" sh -c \
    'exec psql \
        -U "$POSTGRES_USER" \
        -d "$1" \
        -v ON_ERROR_STOP=1 \
        -c "CREATE EXTENSION IF NOT EXISTS timescaledb" \
        -c "SELECT timescaledb_pre_restore()"' \
    sh \
    "${target_database}" \
    >/dev/null

restore_mode_enabled=true

docker exec -i "${container_name}" sh -c \
    'exec pg_restore \
        --format=custom \
        --exit-on-error \
        --no-owner \
        --no-privileges \
        --username "$POSTGRES_USER" \
        --dbname "$1"' \
    sh \
    "${target_database}" \
    <"${archive_path}"

docker exec "${container_name}" sh -c \
    'exec psql \
        -U "$POSTGRES_USER" \
        -d "$1" \
        -v ON_ERROR_STOP=1 \
        -c "SELECT timescaledb_post_restore()" \
        -c "ANALYZE"' \
    sh \
    "${target_database}" \
    >/dev/null

restore_mode_enabled=false
trap - EXIT

echo "restore_result=RESTORED"
echo "target_container=${container_name}"
echo "target_database=${target_database}"
echo "postgres_major=${target_postgres_major}"
echo "timescaledb_version=${target_timescaledb_version}"
