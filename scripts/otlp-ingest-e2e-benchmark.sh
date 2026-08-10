#!/usr/bin/env bash

set -Eeuo pipefail

sender="/home/huning/aerotrace/scripts/otlp-ingest-throughput.py"

db_container="aerotrace-timescaledb"
backend_container="aerotrace-backend"
collector_container="aerotrace-otel-collector"

collector_metrics="http://127.0.0.1:8888/metrics"

total_spans="${TOTAL_SPANS:-1000}"
batch_size="${BATCH_SIZE:-50}"
concurrency="${CONCURRENCY:-4}"

sender_output="$(
  mktemp \
    /tmp/aerotrace-e2e-sender.XXXXXX
)"


cleanup() {
  rm -f \
    "${sender_output}"
}

trap cleanup EXIT INT TERM


positive_integer() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]]
}


metric_value() {
  local metric_name="$1"
  local value

  value="$(
    curl \
      --silent \
      --show-error \
      "${collector_metrics}" |
    awk \
      -v metric_name="${metric_name}" \
      'index($0, metric_name "{") == 1 && index($0, "exporter=\"otlp_http/aerotrace\"") > 0 { print $2; exit }'
  )"

  if [ -z "${value}" ]; then
    printf \
      'Collector metric not found: %s\n' \
      "${metric_name}" \
      >&2
    return 1
  fi

  printf '%s\n' \
    "${value}"
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


if ! positive_integer "${total_spans}"; then
  echo "TOTAL_SPANS must be a positive integer."
  exit 10
fi

if ! positive_integer "${batch_size}"; then
  echo "BATCH_SIZE must be a positive integer."
  exit 11
fi

if ! positive_integer "${concurrency}"; then
  echo "CONCURRENCY must be a positive integer."
  exit 12
fi


echo "===== Configuration ====="

printf 'Total spans: %s\n' \
  "${total_spans}"

printf 'Batch size:  %s\n' \
  "${batch_size}"

printf 'Concurrency: %s\n' \
  "${concurrency}"


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
    --format '{{.State.Status}}'
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

printf 'Backend:   %s\n' "${backend_state}"
printf 'Collector: %s\n' "${collector_state}"
printf 'DB:        %s\n' "${db_state}"
printf 'Queue:     %s\n' "${queue_before}"
printf 'In flight: %s\n' "${in_flight_before}"

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
  echo "Collector is not idle before benchmark."
  exit 23
fi


echo
echo "===== Starting benchmark ====="

benchmark_start_ns="$(
  date '+%s%N'
)"

python3 \
  "${sender}" \
  --total-spans "${total_spans}" \
  --batch-size "${batch_size}" \
  --concurrency "${concurrency}" \
  2>&1 |
tee \
  "${sender_output}"

sender_rc="${PIPESTATUS[0]}"

if [ "${sender_rc}" -ne 0 ]; then
  echo "Sender benchmark failed."
  exit "${sender_rc}"
fi

send_complete_ns="$(
  date '+%s%N'
)"


span_prefix="$(
  grep \
    '^Span prefix:' \
    "${sender_output}" |
  tail -n 1 |
  sed -E \
    's/^Span prefix:[[:space:]]+//'
)"

if [ -z "${span_prefix}" ]; then
  echo "Could not determine test span prefix."
  exit 30
fi

printf 'Tracking prefix: %s\n' \
  "${span_prefix}"


echo
echo "===== Waiting for DB and Collector completion ====="

db_complete_ns=""
pipeline_complete_ns=""

final_db_count="0"
final_queue=""
final_in_flight=""

for attempt in $(seq 1 300)
do
  db_count="$(
    count_test_spans
  )"

  queue_now="$(
    metric_value \
      otelcol_exporter_queue_size
  )"

  in_flight_now="$(
    metric_value \
      otelcol_exporter_in_flight_requests
  )"

  printf \
    'attempt=%03d db=%s/%s queue=%s in_flight=%s\n' \
    "${attempt}" \
    "${db_count}" \
    "${total_spans}" \
    "${queue_now}" \
    "${in_flight_now}"

  if \
    [ -z "${db_complete_ns}" ] &&
    [ "${db_count}" = "${total_spans}" ]
  then
    db_complete_ns="$(
      date '+%s%N'
    )"
  fi

  if \
    [ "${db_count}" -gt "${total_spans}" ]
  then
    echo "Duplicate test spans detected."
    exit 31
  fi

  if \
    [ "${db_count}" = "${total_spans}" ] &&
    [ "${queue_now}" = "0" ] &&
    [ "${in_flight_now}" = "0" ]
  then
    pipeline_complete_ns="$(
      date '+%s%N'
    )"

    final_db_count="${db_count}"
    final_queue="${queue_now}"
    final_in_flight="${in_flight_now}"

    break
  fi

  sleep 0.1
done


if [ -z "${pipeline_complete_ns}" ]; then
  echo "Pipeline did not complete within timeout."
  exit 32
fi


echo
echo "===== Timing ====="

python3 - \
  "${benchmark_start_ns}" \
  "${send_complete_ns}" \
  "${db_complete_ns}" \
  "${pipeline_complete_ns}" \
  "${total_spans}" <<'PY'
import sys

start = int(sys.argv[1])
send_done = int(sys.argv[2])
db_done = int(sys.argv[3])
pipeline_done = int(sys.argv[4])
spans = int(sys.argv[5])


def seconds(end, begin):
    return (end - begin) / 1_000_000_000


send_seconds = seconds(send_done, start)
db_seconds = seconds(db_done, start)
pipeline_seconds = seconds(pipeline_done, start)

print(
    f"Wrapper send elapsed sec:       "
    f"{send_seconds:.6f}"
)

print(
    f"DB completion elapsed sec:      "
    f"{db_seconds:.6f}"
)

print(
    f"Pipeline completion elapsed sec:"
    f" {pipeline_seconds:.6f}"
)

print(
    f"Observed DB completion spans/sec: "
    f"{spans / db_seconds:.2f}"
)

print(
    f"Observed pipeline spans/sec:      "
    f"{spans / pipeline_seconds:.2f}"
)
PY


echo
echo "===== FINAL STATE ====="

printf 'DB count:   %s/%s\n' \
  "${final_db_count}" \
  "${total_spans}"

printf 'Queue:      %s\n' \
  "${final_queue}"

printf 'In flight:  %s\n' \
  "${final_in_flight}"

echo
echo "OTLP end-to-end benchmark: PASS"
