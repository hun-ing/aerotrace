#!/usr/bin/env bash

set -Eeuo pipefail

umask 077

container_name=""
output_dir=""

usage() {
    cat <<'EOF'
Usage:
  backup-timescaledb.sh \
    --container <running-timescaledb-container> \
    --output-dir <secure-host-directory>

Creates one PostgreSQL custom-format archive and sibling .metadata/.sha256 files.
The database name and role are read inside the container from POSTGRES_DB and
POSTGRES_USER. Secret values are not accepted as command arguments.
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
        --output-dir)
            (( $# >= 2 )) || fail "--output-dir requires a value."
            output_dir="$2"
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
[[ -n "${output_dir}" ]] || fail "--output-dir is required."
[[ "${output_dir}" == /* ]] ||
    fail "--output-dir must be an absolute path."
[[ "${container_name}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] ||
    fail "Container name contains unsupported characters."

for required_command in \
    awk chmod date docker install mktemp mv sha256sum stat; do
    require_command "${required_command}"
done

container_state="$(
    docker inspect \
        --format '{{.State.Status}}' \
        "${container_name}" \
        2>/dev/null ||
        true
)"

[[ "${container_state}" == "running" ]] ||
    fail "TimescaleDB container is not running: ${container_name}"

if [[ -e "${output_dir}" && ! -d "${output_dir}" ]]; then
    fail "Output path exists but is not a directory: ${output_dir}"
fi

if [[ ! -e "${output_dir}" ]]; then
    install -d -m 0700 "${output_dir}"
fi

[[ ! -L "${output_dir}" ]] ||
    fail "Output directory must not be a symbolic link: ${output_dir}"

output_mode="$(stat -c '%a' "${output_dir}")"
output_mode_value="$(( 8#${output_mode} ))"

if (( (output_mode_value & 077) != 0 )); then
    fail \
        "Output directory must not grant group/other permissions: " \
        "${output_dir} mode=${output_mode}"
fi

source_info="$(
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
                    current_database(),
                    current_user,
                    current_setting('\''server_version_num'\''),
                    (
                        SELECT extversion
                        FROM pg_extension
                        WHERE extname = '\''timescaledb'\''
                    )
            "
    '
)"

IFS='|' read -r \
    source_database \
    source_username \
    postgres_server_version_num \
    timescaledb_version \
    <<<"${source_info}"

[[ "${postgres_server_version_num}" =~ ^[0-9]+$ ]] ||
    fail "Could not determine the PostgreSQL server version."
[[ -n "${timescaledb_version}" ]] ||
    fail "TimescaleDB extension is not installed in the source database."

postgres_major="$(( postgres_server_version_num / 10000 ))"
pg_dump_version="$(
    docker exec "${container_name}" pg_dump --version
)"

created_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
archive_id="$(date -u +'%Y%m%dT%H%M%SZ')"
archive_name="aerotrace-db-${archive_id}.dump"
archive_path="${output_dir}/${archive_name}"
metadata_path="${output_dir}/aerotrace-db-${archive_id}.metadata"
checksum_path="${output_dir}/aerotrace-db-${archive_id}.sha256"

for final_path in \
    "${archive_path}" \
    "${metadata_path}" \
    "${checksum_path}"; do
    [[ ! -e "${final_path}" ]] ||
        fail "Refusing to overwrite an existing backup file: ${final_path}"
done

archive_tmp="$(mktemp "${output_dir}/.${archive_name}.XXXXXX")"
metadata_tmp="$(mktemp "${output_dir}/.metadata.XXXXXX")"
checksum_tmp="$(mktemp "${output_dir}/.sha256.XXXXXX")"

cleanup_temporary_files() {
    rm -f -- "${archive_tmp}" "${metadata_tmp}" "${checksum_tmp}"
}

trap cleanup_temporary_files EXIT

docker exec "${container_name}" sh -c '
    exec pg_dump \
        --format=custom \
        --compress=6 \
        --no-owner \
        --no-privileges \
        --username "$POSTGRES_USER" \
        --dbname "$POSTGRES_DB"
' >"${archive_tmp}"

[[ -s "${archive_tmp}" ]] || fail "pg_dump created an empty archive."

docker exec -i "${container_name}" \
    pg_restore --list \
    <"${archive_tmp}" \
    >/dev/null

archive_sha256="$(sha256sum "${archive_tmp}" | awk '{print $1}')"
archive_size_bytes="$(stat -c '%s' "${archive_tmp}")"

cat >"${metadata_tmp}" <<EOF
format=aerotrace-timescaledb-logical-backup-v1
created_at=${created_at}
archive_name=${archive_name}
archive_format=postgresql-custom
archive_size_bytes=${archive_size_bytes}
archive_sha256=${archive_sha256}
source_container=${container_name}
source_database=${source_database}
source_username=${source_username}
postgres_server_version_num=${postgres_server_version_num}
postgres_major=${postgres_major}
timescaledb_version=${timescaledb_version}
pg_dump_version=${pg_dump_version}
parallel_restore_allowed=false
EOF

printf '%s  %s\n' \
    "${archive_sha256}" \
    "${archive_name}" \
    >"${checksum_tmp}"

chmod 0600 "${archive_tmp}" "${metadata_tmp}" "${checksum_tmp}"

mv "${archive_tmp}" "${archive_path}"
mv "${metadata_tmp}" "${metadata_path}"
mv "${checksum_tmp}" "${checksum_path}"

trap - EXIT

echo "backup_result=CREATED"
echo "archive_path=${archive_path}"
echo "metadata_path=${metadata_path}"
echo "checksum_path=${checksum_path}"
echo "archive_size_bytes=${archive_size_bytes}"
echo "postgres_major=${postgres_major}"
echo "timescaledb_version=${timescaledb_version}"
