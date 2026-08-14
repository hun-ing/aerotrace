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

sender_workers="${SENDER_WORKERS:-4}"
sender_queue_capacity="${SENDER_QUEUE_CAPACITY:-32}"
max_rate_error_pct="${MAX_RATE_ERROR_PCT:-1.0}"
max_p99_lag_intervals="${MAX_P99_LAG_INTERVALS:-2.0}"

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

receiver_metric_value() {
  local metric_name="$1"

  curl \
    --silent \
    --show-error \
    "${collector_metrics}" |
  awk \
    -v metric_name="${metric_name}" \
    'index($0, metric_name "{") == 1 &&
     index($0, "receiver=\"otlp\"") > 0 &&
     index($0, "transport=\"http\"") > 0 {
       print $2
       exit
     }'
}


metric_delta() {
  python3 - "$1" "$2" <<'PY'
from decimal import Decimal
import sys

before = Decimal(sys.argv[1])
after = Decimal(sys.argv[2])
delta = after - before

if delta != delta.to_integral_value():
    raise SystemExit(
        f"Metric delta is not an integer: {delta}"
    )

print(int(delta))
PY
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
  "${monitor_interval}" \
  "${sender_workers}" \
  "${sender_queue_capacity}"
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

printf 'Sender workers:   %s\n' \
  "${sender_workers}"

printf 'Sender queue:     %s\n' \
  "${sender_queue_capacity}"

printf 'Max rate error:   %s%%\n' \
  "${max_rate_error_pct}"

printf 'Max p99 lag:      %s intervals\n' \
  "${max_p99_lag_intervals}"

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

enqueue_failed_before="$(
  metric_value \
    otelcol_exporter_enqueue_failed_spans
)"

sent_spans_before="$(
  metric_value \
    otelcol_exporter_sent_spans
)"

accepted_spans_before="$(
  receiver_metric_value \
    otelcol_receiver_accepted_spans
)"

refused_spans_before="$(
  receiver_metric_value \
    otelcol_receiver_refused_spans
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

printf 'Enqueue failed: %s\n' \
  "${enqueue_failed_before}"

printf 'Receiver refused: %s\n' \
  "${refused_spans_before}"

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
  --workers "${sender_workers}" \
  --queue-capacity "${sender_queue_capacity}" \
  --max-rate-error-pct "${max_rate_error_pct}" \
  --max-p99-lag-intervals "${max_p99_lag_intervals}" \
  2>&1 |
tee \
  "${sender_log}"

sender_rc="${PIPESTATUS[0]}"

if [ "${sender_rc}" -ne 0 ]; then
  printf \
    'Sender exited non-zero: %s; continuing pipeline verification.\n' \
    "${sender_rc}"
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
    [ "${final_queue}" = "0" ] &&
    [ "${final_in_flight}" = "0" ]
  then
    completion_ok=1
    echo "Collector drain complete."
    break
  fi

  sleep 1
done


if [ "${completion_ok}" -ne 1 ]; then
  echo "Sustained pipeline did not complete."
  exit 33
fi

echo
echo "===== Waiting for DB settle ====="

db_settle_ok=0

for attempt in $(seq 1 10)
do
  final_db_count="$(
    count_test_spans
  )"

  printf \
    'attempt=%02d db=%s/%s\n' \
    "${attempt}" \
    "${final_db_count}" \
    "${total_spans}"

  if [ "${final_db_count}" -gt "${total_spans}" ]; then
    echo "Duplicate test spans detected."
    exit 32
  fi

  if [ "${final_db_count}" = "${total_spans}" ]; then
    db_settle_ok=1
    echo "DB settle complete."
    break
  fi

  sleep 1
done


if [ "${db_settle_ok}" -ne 1 ]; then
  echo \
    "DB did not reach expected count after Collector drain; continuing integrity verification."
fi

enqueue_failed_after="$(
  metric_value \
    otelcol_exporter_enqueue_failed_spans
)"

sent_spans_after="$(
  metric_value \
    otelcol_exporter_sent_spans
)"

accepted_spans_after="$(
  receiver_metric_value \
    otelcol_receiver_accepted_spans
)"

refused_spans_after="$(
  receiver_metric_value \
    otelcol_receiver_refused_spans
)"


enqueue_failed_delta="$(
  metric_delta \
    "${enqueue_failed_before}" \
    "${enqueue_failed_after}"
)"

sent_spans_delta="$(
  metric_delta \
    "${sent_spans_before}" \
    "${sent_spans_after}"
)"

accepted_spans_delta="$(
  metric_delta \
    "${accepted_spans_before}" \
    "${accepted_spans_after}"
)"

refused_spans_delta="$(
  metric_delta \
    "${refused_spans_before}" \
    "${refused_spans_after}"
)"

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

  printf 'Collector accepted delta: %s\n' \
    "${accepted_spans_delta}"

  printf 'Collector refused delta: %s\n' \
    "${refused_spans_delta}"

  printf 'Exporter sent delta: %s\n' \
    "${sent_spans_delta}"

  printf 'Exporter enqueue failed delta: %s\n' \
    "${enqueue_failed_delta}"

  printf 'Sender exit code: %s\n' \
    "${sender_rc}"

  printf 'Sender log: %s\n' \
    "${sender_log}"

  printf 'Resource data: %s\n' \
    "${resource_file}"
} |
tee \
  "${summary_file}"


data_integrity_ok=1

if [ "${final_db_count}" != "${total_spans}" ]; then
  data_integrity_ok=0

  printf \
    'Data loss detected: DB stored %s/%s spans.\n' \
    "${final_db_count}" \
    "${total_spans}"
fi

if [ "${enqueue_failed_delta}" -ne 0 ]; then
  data_integrity_ok=0

  printf \
    'Data loss detected: exporter enqueue failed delta=%s.\n' \
    "${enqueue_failed_delta}"
fi

if [ "${refused_spans_delta}" -ne 0 ]; then
  data_integrity_ok=0

  printf \
    'Data loss detected: receiver refused delta=%s.\n' \
    "${refused_spans_delta}"
fi


echo

if [ "${data_integrity_ok}" -ne 1 ]; then
  echo "Sustained load test: FAIL (data integrity)"
  exit 34
fi

if [ "${sender_rc}" -ne 0 ]; then
  echo "Sustained load test: FAIL (sender validity)"
  exit "${sender_rc}"
fi

echo "Sustained load test: PASS"
