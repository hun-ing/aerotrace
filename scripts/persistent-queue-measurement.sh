#!/usr/bin/env bash

set -Eeuo pipefail

backend_container="aerotrace-backend"
collector_container="aerotrace-otel-collector"
db_container="aerotrace-timescaledb"

collector_endpoint="http://127.0.0.1:4318/v1/traces"
collector_metrics="http://127.0.0.1:8888/metrics"

volume_path="/var/lib/docker/volumes/aerotrace-otelcol-data/_data"

payload_file="/tmp/aerotrace-pq-measure-payload.json"

test_span_count="${TEST_SPAN_COUNT:-100}"

if ! [[ "${test_span_count}" =~ ^[1-9][0-9]*$ ]]; then
  echo "TEST_SPAN_COUNT must be a positive integer."
  exit 10
fi
backend_paused=0


cleanup() {
  local status=$?

  trap - EXIT INT TERM

  if [ "${backend_paused}" -eq 1 ]; then
    echo
    echo "Cleanup: unpausing AeroTrace backend."

    docker unpause \
      "${backend_container}" \
      >/dev/null 2>&1 \
      || true
  fi

  rm -f \
    "${payload_file}"

  exit "${status}"
}

trap cleanup EXIT INT TERM


volume_apparent_bytes() {
  sudo du \
    -sb \
    "${volume_path}" |
  awk '{print $1}'
}


volume_allocated_bytes() {
  sudo du \
    -s \
    --block-size=1 \
    "${volume_path}" |
  awk '{print $1}'
}


print_queue_metrics() {
  curl \
    --silent \
    --show-error \
    "${collector_metrics}" |
  grep \
    -Ei \
    '^otelcol_.*(queue|enqueue|send_failed|sent_spans|retry|accepted_spans|refused_spans)' \
    || true
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
          FROM public.${span_table}
          WHERE name LIKE '${span_prefix}%';
        \"
    "
}


echo "===== Discovering span table ====="

mapfile -t candidate_tables < <(
  docker exec \
    "${db_container}" \
    sh \
    -c '
      psql \
        -U "$POSTGRES_USER" \
        -d "$POSTGRES_DB" \
        -At \
        -v ON_ERROR_STOP=1 \
        -c "
          SELECT table_name
          FROM information_schema.columns
          WHERE table_schema = '\''public'\''
            AND column_name IN (
              '\''trace_id'\'',
              '\''span_id'\'',
              '\''name'\''
            )
          GROUP BY table_name
          HAVING count(DISTINCT column_name) = 3
          ORDER BY table_name;
        "
    '
)

if [ "${#candidate_tables[@]}" -ne 1 ]; then
  echo "Expected exactly one span table."
  printf '  %s\n' "${candidate_tables[@]:-<none>}"
  exit 20
fi

span_table="${candidate_tables[0]}"

if ! [[ "${span_table}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
  echo "Unexpected table identifier: ${span_table}"
  exit 21
fi

echo "Span table: public.${span_table}"


echo
echo "===== Creating test run ====="

run_id="$(
  printf \
    '%s-%s' \
    "$(date -u '+%Y%m%dT%H%M%SZ')" \
    "$$"
)"

span_prefix="aerotrace-pq-measure-${run_id}-"

printf 'Run ID:      %s\n' "${run_id}"
printf 'Span prefix: %s\n' "${span_prefix}"
printf 'Span count:  %s\n' "${test_span_count}"


echo
echo "===== Confirming clean DB state ====="

db_before="$(
  count_test_spans
)"

printf 'DB count before: %s\n' "${db_before}"

if [ "${db_before}" != "0" ]; then
  echo "Unexpected existing test spans."
  exit 22
fi


echo
echo "===== Baseline persistent storage ====="

apparent_before="$(
  volume_apparent_bytes
)"

allocated_before="$(
  volume_allocated_bytes
)"

printf 'Apparent bytes before:  %s\n' "${apparent_before}"
printf 'Allocated bytes before: %s\n' "${allocated_before}"


echo
echo "===== Baseline Collector metrics ====="

print_queue_metrics


echo
echo "===== Pausing backend ====="

docker pause \
  "${backend_container}" \
  >/dev/null

backend_paused=1

paused="$(
  docker inspect \
    "${backend_container}" \
    --format '{{.State.Paused}}'
)"

printf 'Backend paused: %s\n' "${paused}"

if [ "${paused}" != "true" ]; then
  echo "Backend pause failed."
  exit 23
fi


echo
echo "===== Sending ${test_span_count} spans ====="

accepted=0

for i in $(seq 1 "${test_span_count}")
do
  trace_id="$(
    openssl rand \
      -hex \
      16
  )"

  span_id="$(
    openssl rand \
      -hex \
      8
  )"

  span_name="$(
    printf \
      '%s%03d' \
      "${span_prefix}" \
      "${i}"
  )"

  start_ns="$(
    python3 - <<'PY'
import time
print(time.time_ns())
PY
  )"

  end_ns="$((start_ns + 1000000))"

  cat > "${payload_file}" <<JSON
{
  "resourceSpans": [
    {
      "resource": {
        "attributes": [
          {
            "key": "service.name",
            "value": {
              "stringValue": "aerotrace-pq-measurement"
            }
          }
        ]
      },
      "scopeSpans": [
        {
          "scope": {
            "name": "aerotrace.manual.queue-measurement"
          },
          "spans": [
            {
              "traceId": "${trace_id}",
              "spanId": "${span_id}",
              "name": "${span_name}",
              "kind": 1,
              "startTimeUnixNano": "${start_ns}",
              "endTimeUnixNano": "${end_ns}",
              "status": {
                "code": 1
              }
            }
          ]
        }
      ]
    }
  ]
}
JSON

  chmod 600 \
    "${payload_file}"

  http_status="$(
    curl \
      --silent \
      --show-error \
      --output /dev/null \
      --write-out '%{http_code}' \
      --connect-timeout 3 \
      --max-time 5 \
      --request POST \
      --header 'Content-Type: application/json' \
      --data-binary "@${payload_file}" \
      "${collector_endpoint}"
  )"

  if [ "${http_status}" != "200" ]; then
    printf \
      'Request %d failed: HTTP %s\n' \
      "${i}" \
      "${http_status}"
    exit 24
  fi

  accepted=$((accepted + 1))

  if [ $((i % 10)) -eq 0 ]; then
    printf \
      'accepted=%d/%d\n' \
      "${accepted}" \
      "${test_span_count}"
  fi
done

printf 'Collector accepted: %s\n' "${accepted}"


echo
echo "===== Waiting for queue state to settle ====="

sleep 5


echo
echo "===== DB check during outage ====="

db_during_outage="$(
  count_test_spans
)"

printf \
  'DB count while backend paused: %s\n' \
  "${db_during_outage}"

if [ "${db_during_outage}" != "0" ]; then
  echo "Unexpected test spans reached DB."
  exit 25
fi


echo
echo "===== Collector metrics while queued ====="

print_queue_metrics


echo
echo "===== Persistent storage while queued ====="

apparent_queued="$(
  volume_apparent_bytes
)"

allocated_queued="$(
  volume_allocated_bytes
)"

apparent_delta="$((apparent_queued - apparent_before))"
allocated_delta="$((allocated_queued - allocated_before))"

printf 'Apparent bytes queued:   %s\n' "${apparent_queued}"
printf 'Allocated bytes queued:  %s\n' "${allocated_queued}"
printf 'Apparent byte delta:     %s\n' "${apparent_delta}"
printf 'Allocated byte delta:    %s\n' "${allocated_delta}"


echo
echo "===== Unpausing backend ====="

docker unpause \
  "${backend_container}" \
  >/dev/null

backend_paused=0


echo
echo "===== Waiting for backend health ====="

backend_recovered=0

for attempt in $(seq 1 30)
do
  status="$(
    docker inspect \
      "${backend_container}" \
      --format '{{.State.Status}}'
  )"

  health="$(
    docker inspect \
      "${backend_container}" \
      --format '{{.State.Health.Status}}'
  )"

  printf \
    'attempt=%02d status=%s health=%s\n' \
    "${attempt}" \
    "${status}" \
    "${health}"

  if \
    [ "${status}" = "running" ] &&
    [ "${health}" = "healthy" ]
  then
    backend_recovered=1
    break
  fi

  sleep 1
done

if [ "${backend_recovered}" -ne 1 ]; then
  echo "Backend recovery failed."
  exit 26
fi


echo
echo "===== Waiting for all spans ====="

final_count=0
recovered=0

for attempt in $(seq 1 60)
do
  final_count="$(
    count_test_spans
  )"

  printf \
    'attempt=%02d DB count=%s/%s\n' \
    "${attempt}" \
    "${final_count}" \
    "${test_span_count}"

  if [ "${final_count}" = "${test_span_count}" ]; then
    recovered=1
    break
  fi

  if [ "${final_count}" -gt "${test_span_count}" ]; then
    echo "Duplicate spans detected."
    exit 27
  fi

  sleep 2
done

if [ "${recovered}" -ne 1 ]; then
  echo "Not all spans reached DB."
  exit 28
fi


echo
echo "===== Metrics after queue drain ====="

print_queue_metrics


echo
echo "===== Persistent storage after drain ====="

sleep 2

apparent_after="$(
  volume_apparent_bytes
)"

allocated_after="$(
  volume_allocated_bytes
)"

printf 'Apparent bytes after:  %s\n' "${apparent_after}"
printf 'Allocated bytes after: %s\n' "${allocated_after}"


echo
echo "===== RESULT ====="

printf 'Requested spans:          %s\n' "${test_span_count}"
printf 'Collector accepted:       %s\n' "${accepted}"
printf 'DB count during outage:   %s\n' "${db_during_outage}"
printf 'Final DB count:           %s\n' "${final_count}"
printf 'Apparent bytes before:    %s\n' "${apparent_before}"
printf 'Apparent bytes queued:    %s\n' "${apparent_queued}"
printf 'Apparent byte delta:      %s\n' "${apparent_delta}"
printf 'Allocated bytes before:   %s\n' "${allocated_before}"
printf 'Allocated bytes queued:   %s\n' "${allocated_queued}"
printf 'Allocated byte delta:     %s\n' "${allocated_delta}"

echo
echo "Persistent queue 100-span measurement: PASS"
