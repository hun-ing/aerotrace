#!/usr/bin/env bash

set -Eeuo pipefail

backend_container="aerotrace-backend"
collector_container="aerotrace-otel-collector"
db_container="aerotrace-timescaledb"

collector_metrics="http://127.0.0.1:8888/metrics"

duration_sec="${DURATION_SEC:-3}"
interval_sec="${INTERVAL_SEC:-1}"
output_file="${OUTPUT_FILE:-}"


positive_integer() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]]
}


exporter_metric() {
  local metrics="$1"
  local metric_name="$2"

  awk \
    -v metric_name="${metric_name}" \
    'index($0, metric_name "{") == 1 && index($0, "exporter=\"otlp_http/aerotrace\"") > 0 { print $2; exit }' \
    <<< "${metrics}"
}


receiver_metric() {
  local metrics="$1"
  local metric_name="$2"

  awk \
    -v metric_name="${metric_name}" \
    'index($0, metric_name "{") == 1 && index($0, "receiver=\"otlp\"") > 0 && index($0, "transport=\"http\"") > 0 { print $2; exit }' \
    <<< "${metrics}"
}


container_stat() {
  local stats="$1"
  local container_name="$2"
  local field="$3"

  awk \
    -F '|' \
    -v container_name="${container_name}" \
    -v field="${field}" \
    '$1 == container_name { print $field; exit }' \
    <<< "${stats}"
}


if ! positive_integer "${duration_sec}"; then
  echo "DURATION_SEC must be a positive integer."
  exit 10
fi

if ! positive_integer "${interval_sec}"; then
  echo "INTERVAL_SEC must be a positive integer."
  exit 11
fi

if [ -z "${output_file}" ]; then
  echo "OUTPUT_FILE is required."
  exit 12
fi


sample_count="$(
  python3 - \
    "${duration_sec}" \
    "${interval_sec}" <<'PY'
import sys

duration = int(sys.argv[1])
interval = int(sys.argv[2])

print(duration // interval + 1)
PY
)"


mkdir -p \
  "$(dirname "${output_file}")"

touch \
  "${output_file}"

chmod 600 \
  "${output_file}"


printf '%s\n' \
  $'timestamp_utc\tsample\tbackend_cpu\tbackend_mem\tbackend_mem_pct\tcollector_cpu\tcollector_mem\tcollector_mem_pct\tdb_cpu\tdb_mem\tdb_mem_pct\tdb_connections\tdb_active\tdb_idle\tdb_max_connections\tqueue_size\tin_flight\taccepted_spans\trefused_spans\tsent_spans' \
  > "${output_file}"


echo "===== Sustained resource monitor ====="

printf 'Duration:    %ss\n' \
  "${duration_sec}"

printf 'Interval:    %ss\n' \
  "${interval_sec}"

printf 'Samples:     %s\n' \
  "${sample_count}"

printf 'Output file: %s\n' \
  "${output_file}"


monitor_start_ns="$(
  date '+%s%N'
)"

for sample in $(seq 1 "${sample_count}")
do
  if [ "${sample}" -gt 1 ]; then
    target_offset_sec="$(
      printf '%s\n' \
        "$(( (sample - 1) * interval_sec ))"
    )"

    sleep_seconds="$(
      python3 - \
        "${monitor_start_ns}" \
        "${target_offset_sec}" <<'PY'
import sys
import time

start_ns = int(sys.argv[1])
offset_sec = int(sys.argv[2])

target_ns = (
    start_ns
    + offset_sec * 1_000_000_000
)

remaining = (
    target_ns - time.time_ns()
) / 1_000_000_000

if remaining <= 0:
    print("0")
else:
    print(f"{remaining:.6f}")
PY
    )"

    if [ "${sleep_seconds}" != "0" ]; then
      sleep \
        "${sleep_seconds}"
    fi
  fi

  timestamp="$(
    date -u '+%Y-%m-%dT%H:%M:%S.%3NZ'
  )"

  stats="$(
    docker stats \
      --no-stream \
      --format \
      '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}' \
      "${backend_container}" \
      "${collector_container}" \
      "${db_container}"
  )"

  backend_cpu="$(
    container_stat \
      "${stats}" \
      "${backend_container}" \
      2
  )"

  backend_mem="$(
    container_stat \
      "${stats}" \
      "${backend_container}" \
      3
  )"

  backend_mem_pct="$(
    container_stat \
      "${stats}" \
      "${backend_container}" \
      4
  )"

  collector_cpu="$(
    container_stat \
      "${stats}" \
      "${collector_container}" \
      2
  )"

  collector_mem="$(
    container_stat \
      "${stats}" \
      "${collector_container}" \
      3
  )"

  collector_mem_pct="$(
    container_stat \
      "${stats}" \
      "${collector_container}" \
      4
  )"

  db_cpu="$(
    container_stat \
      "${stats}" \
      "${db_container}" \
      2
  )"

  db_mem="$(
    container_stat \
      "${stats}" \
      "${db_container}" \
      3
  )"

  db_mem_pct="$(
    container_stat \
      "${stats}" \
      "${db_container}" \
      4
  )"

  db_connections="$(
    docker exec \
      "${db_container}" \
      sh \
      -c "
        psql \
          -U \"\$POSTGRES_USER\" \
          -d \"\$POSTGRES_DB\" \
          -At \
          -F '|' \
          -v ON_ERROR_STOP=1 \
          -c \"
            SELECT
              count(*) FILTER (
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
              ),
              count(*) FILTER (
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND state = 'active'
              ),
              count(*) FILTER (
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND state = 'idle'
              ),
              current_setting('max_connections')::int
            FROM pg_stat_activity;
          \"
      "
  )"

  IFS='|' read -r \
    db_connection_total \
    db_connection_active \
    db_connection_idle \
    db_max_connections \
    <<< "${db_connections}"

  metrics="$(
    curl \
      --silent \
      --show-error \
      "${collector_metrics}"
  )"

  queue_size="$(
    exporter_metric \
      "${metrics}" \
      otelcol_exporter_queue_size
  )"

  in_flight="$(
    exporter_metric \
      "${metrics}" \
      otelcol_exporter_in_flight_requests
  )"

  sent_spans="$(
    exporter_metric \
      "${metrics}" \
      otelcol_exporter_sent_spans
  )"

  accepted_spans="$(
    receiver_metric \
      "${metrics}" \
      otelcol_receiver_accepted_spans
  )"

  refused_spans="$(
    receiver_metric \
      "${metrics}" \
      otelcol_receiver_refused_spans
  )"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${timestamp}" \
    "${sample}" \
    "${backend_cpu}" \
    "${backend_mem}" \
    "${backend_mem_pct}" \
    "${collector_cpu}" \
    "${collector_mem}" \
    "${collector_mem_pct}" \
    "${db_cpu}" \
    "${db_mem}" \
    "${db_mem_pct}" \
    "${db_connection_total}" \
    "${db_connection_active}" \
    "${db_connection_idle}" \
    "${db_max_connections}" \
    "${queue_size}" \
    "${in_flight}" \
    "${accepted_spans}" \
    "${refused_spans}" \
    "${sent_spans}" \
    >> "${output_file}"

  printf \
    'sample=%s/%s queue=%s in_flight=%s db_conn=%s active=%s\n' \
    "${sample}" \
    "${sample_count}" \
    "${queue_size}" \
    "${in_flight}" \
    "${db_connection_total}" \
    "${db_connection_active}"

done


echo
echo "Resource monitor: PASS"
