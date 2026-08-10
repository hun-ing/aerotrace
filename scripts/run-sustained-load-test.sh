#!/usr/bin/env bash

set -uo pipefail

sender="/home/huning/aerotrace/scripts/otlp-ingest-sustained.py"
monitor="/home/huning/aerotrace/scripts/sample-sustained-resources.sh"

backend_container="aerotrace-backend"
collector_container="aerotrace-otel-collector"
db_container="aerotrace-timescaledb"

collector_metrics="http://127.0.0.1:8888/metrics"

target_rate="${TARGET_SPANS_PER_SEC:-500}"
duration_sec="${DURATION_SEC:-60}"
batch_size="${BATCH_SIZE:-50}"
monitor_interval="${MONITOR_INTERVAL_SEC:-5}"

pre_load_sec=5
post_load_sec=5

monitor_duration="$(
  printf '%s\n'     "$(( duration_sec + pre_load_sec + post_load_sec ))"
)"

total_spans="$(
  printf '%s\n'     "$(( target_rate * duration_sec ))"
)"

timestamp="$(
  date -u '+%Y%m%dT%H%M%SZ'
)"

result_dir="$(
  printf \
    '/home/huning/aerotrace/benchmark-results/sustained-%s' \
    "${timestamp}"
)"

sender_log="${result_dir}/sender.txt"
resource_file="${result_dir}/resources.tsv"
monitor_log="${result_dir}/monitor.txt"
summary_file="${result_dir}/summary.txt"

monitor_pid=""


cleanup() {
  if \
    [ -n "${monitor_pid}" ] &&
    kill -0 "${monitor_pid}" 2>/dev/null
  then
    kill \
      "${monitor_pid}" \
      2>/dev/null \
      || true

    wait \
      "${monitor_pid}" \
      2>/dev/null \
      || true
  fi
}

trap cleanup EXIT INT TERM


positive_integer() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]]
}


metric_value() {
  local metric_name="$1"

  curl \
    --silent \
    --show-error \
    "${collector_metrics}" |
  awk \
    -v metric_name="${metric_name}" \
    'index($0, metric_name "{") == 1 && index($0, "exporter=\"otlp_http/aerotrace\"") > 0 { print $2; exit }'
}


count_test_spans() {
  docker exec \
    "${db_container}" \
    sh \
    -c "
      psql \
        -U \"\$POSTGRES_USER\" \
        -d \"\$POSTGRES_DB\" \
        -At \
        -v ON_ERROR_STOP=1 \
        -c \"
          SELECT count(*)
          FROM public.spans
          WHERE name LIKE '${span_prefix}%';
        \"
    "
}


for value in \
  "${target_rate}" \
  "${duration_sec}" \
  "${batch_size}" \
  "${monitor_interval}"
do
  if ! positive_integer "${value}"; then
    echo "All numeric parameters must be positive integers."
    exit 10
  fi
done


umask 077

mkdir -p \
  "${result_dir}"


echo "===== Configuration ====="

printf 'Target spans/sec: %s\n' \
  "${target_rate}"

printf 'Duration sec:     %s\n' \
  "${duration_sec}"

printf 'Batch size:       %s\n' \
  "${batch_size}"

printf 'Total spans:      %s\n' \
  "${total_spans}"

printf 'Monitor duration: %s\n' \
  "${monitor_duration}"

printf 'Monitor interval: %s\n' \
  "${monitor_interval}"

printf 'Result directory: %s\n' \
  "${result_dir}"


echo
echo "===== Preflight ====="

backend_state="$(
  docker inspect \
    "${backend_container}" \
    --format \
    '{{.State.Status}} {{.State.Paused}} {{.State.Health.Status}}'
)"

collector_state="$(
  docker inspect \
    "${collector_container}" \
    --format \
    '{{.State.Status}}'
)"

db_state="$(
  docker inspect \
    "${db_container}" \
    --format \
    '{{.State.Status}} {{.State.Health.Status}}'
)"

queue_before="$(
  metric_value \
    otelcol_exporter_queue_size
)"

in_flight_before="$(
  metric_value \
    otelcol_exporter_in_flight_requests
)"

printf 'Backend:   %s\n' \
  "${backend_state}"

printf 'Collector: %s\n' \
  "${collector_state}"

printf 'DB:        %s\n' \
  "${db_state}"

printf 'Queue:     %s\n' \
  "${queue_before}"

printf 'In flight: %s\n' \
  "${in_flight_before}"


if [ "${backend_state}" != "running false healthy" ]; then
  echo "Backend preflight failed."
  exit 20
fi

if [ "${collector_state}" != "running" ]; then
  echo "Collector preflight failed."
  exit 21
fi

if [ "${db_state}" != "running healthy" ]; then
  echo "Database preflight failed."
  exit 22
fi

if \
  [ "${queue_before}" != "0" ] ||
  [ "${in_flight_before}" != "0" ]
then
  echo "Collector is not idle."
  exit 23
fi


echo
echo "===== Starting resource monitor ====="

DURATION_SEC="${monitor_duration}" \
INTERVAL_SEC="${monitor_interval}" \
OUTPUT_FILE="${resource_file}" \
  "${monitor}" \
  > "${monitor_log}" \
  2>&1 &

monitor_pid="$!"

printf 'Monitor PID: %s\n' \
  "${monitor_pid}"

echo "Waiting ${pre_load_sec}s for idle baseline..."

sleep \
  "${pre_load_sec}"


if ! kill -0 "${monitor_pid}" 2>/dev/null; then
  echo "Resource monitor exited before load started."

  cat \
    "${monitor_log}"

  exit 30
fi


echo
echo "===== Starting sustained load ====="

python3 \
  "${sender}" \
  --target-spans-per-sec "${target_rate}" \
  --duration-sec "${duration_sec}" \
  --batch-size "${batch_size}" \
  2>&1 |
tee \
  "${sender_log}"

sender_rc="${PIPESTATUS[0]}"

if [ "${sender_rc}" -ne 0 ]; then
  echo "Sustained sender failed."
  exit "${sender_rc}"
fi


span_prefix="$(
  grep \
    '^Span prefix:' \
    "${sender_log}" |
  tail -n 1 |
  sed -E \
    's/^Span prefix:[[:space:]]+//'
)"

if [ -z "${span_prefix}" ]; then
  echo "Could not determine span prefix."
  exit 31
fi

printf 'Tracking prefix: %s\n' \
  "${span_prefix}"


echo
echo "===== Waiting for DB and Collector drain ====="

completion_ok=0
final_db_count=0
final_queue=""
final_in_flight=""

for attempt in $(seq 1 120)
do
  final_db_count="$(
    count_test_spans
  )"

  final_queue="$(
    metric_value \
      otelcol_exporter_queue_size
  )"

  final_in_flight="$(
    metric_value \
      otelcol_exporter_in_flight_requests
  )"

  printf \
    'attempt=%03d db=%s/%s queue=%s in_flight=%s\n' \
    "${attempt}" \
    "${final_db_count}" \
    "${total_spans}" \
    "${final_queue}" \
    "${final_in_flight}"

  if [ "${final_db_count}" -gt "${total_spans}" ]; then
    echo "Duplicate test spans detected."
    exit 32
  fi

  if \
    [ "${final_db_count}" = "${total_spans}" ] &&
    [ "${final_queue}" = "0" ] &&
    [ "${final_in_flight}" = "0" ]
  then
    completion_ok=1
    echo "Sustained pipeline completion: OK"
    break
  fi

  sleep 1
done


if [ "${completion_ok}" -ne 1 ]; then
  echo "Sustained pipeline did not complete."
  exit 33
fi


echo
echo "===== Waiting for resource monitor ====="

wait \
  "${monitor_pid}"

monitor_rc="$?"

monitor_pid=""

if [ "${monitor_rc}" -ne 0 ]; then
  echo "Resource monitor failed."

  cat \
    "${monitor_log}"

  exit "${monitor_rc}"
fi


echo
echo "===== FINAL RESULT ====="

{
  printf 'Target spans/sec: %s\n' \
    "${target_rate}"

  printf 'Duration sec: %s\n' \
    "${duration_sec}"

  printf 'Expected spans: %s\n' \
    "${total_spans}"

  printf 'DB count: %s/%s\n' \
    "${final_db_count}" \
    "${total_spans}"

  printf 'Queue: %s\n' \
    "${final_queue}"

  printf 'In flight: %s\n' \
    "${final_in_flight}"

  printf 'Sender log: %s\n' \
    "${sender_log}"

  printf 'Resource data: %s\n' \
    "${resource_file}"
} |
tee \
  "${summary_file}"


echo
echo "Sustained load test: PASS"
