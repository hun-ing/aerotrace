#!/usr/bin/env bash

set -Eeuo pipefail

benchmark="/home/huning/aerotrace/scripts/otlp-ingest-e2e-benchmark.sh"

runs="${RUNS:-5}"
total_spans="${TOTAL_SPANS:-1000}"
batch_size="${BATCH_SIZE:-50}"
concurrency="${CONCURRENCY:-4}"

timestamp="$(
  date -u '+%Y%m%dT%H%M%SZ'
)"

result_dir="$(
  printf \
    '/home/huning/aerotrace/benchmark-results/e2e-%s' \
    "${timestamp}"
)"

mkdir -p \
  "${result_dir}"

chmod 700 \
  "${result_dir}"

summary_file="${result_dir}/summary.tsv"

printf \
  'run\taccepted_spans_per_sec\tdb_spans_per_sec\tpipeline_spans_per_sec\tdb_elapsed_sec\tpipeline_elapsed_sec\n' \
  > "${summary_file}"


for run in $(seq 1 "${runs}")
do
  log_file="$(
    printf \
      '%s/run-%02d.txt' \
      "${result_dir}" \
      "${run}"
  )"

  echo
  printf \
    '===== Benchmark run %d/%d =====\n' \
    "${run}" \
    "${runs}"

  TOTAL_SPANS="${total_spans}" \
  BATCH_SIZE="${batch_size}" \
  CONCURRENCY="${concurrency}" \
    "${benchmark}" \
    2>&1 |
  tee \
    "${log_file}"

  benchmark_rc="${PIPESTATUS[0]}"

  if [ "${benchmark_rc}" -ne 0 ]; then
    echo "Benchmark run ${run} failed."
    exit "${benchmark_rc}"
  fi

  accepted_rate="$(
    awk \
      '/^Accepted spans\/sec:/ { print $3; exit }' \
      "${log_file}"
  )"

  db_rate="$(
    awk \
      '/^Observed DB completion spans\/sec:/ { print $5; exit }' \
      "${log_file}"
  )"

  pipeline_rate="$(
    awk \
      '/^Observed pipeline spans\/sec:/ { print $4; exit }' \
      "${log_file}"
  )"

  db_elapsed="$(
    awk \
      '/^DB completion elapsed sec:/ { print $5; exit }' \
      "${log_file}"
  )"

  pipeline_elapsed="$(
    awk \
      '/^Pipeline completion elapsed sec:/ { print $5; exit }' \
      "${log_file}"
  )"

  printf \
    '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${run}" \
    "${accepted_rate}" \
    "${db_rate}" \
    "${pipeline_rate}" \
    "${db_elapsed}" \
    "${pipeline_elapsed}" \
    >> "${summary_file}"

  sleep 2
done


echo
echo "===== SERIES RESULT ====="

cat \
  "${summary_file}"

echo
echo "===== STATISTICS ====="

python3 - \
  "${summary_file}" <<'PY'
import csv
import statistics
import sys

path = sys.argv[1]

rows = []

with open(path, encoding="utf-8") as f:
    reader = csv.DictReader(
        f,
        delimiter="\t",
    )

    for row in reader:
        rows.append(row)


def values(name):
    return [
        float(row[name])
        for row in rows
    ]


for label, field in (
    (
        "Accepted spans/sec",
        "accepted_spans_per_sec",
    ),
    (
        "DB spans/sec",
        "db_spans_per_sec",
    ),
    (
        "Pipeline spans/sec",
        "pipeline_spans_per_sec",
    ),
):
    data = values(field)

    print(label)
    print(f"  runs:   {len(data)}")
    print(f"  min:    {min(data):.2f}")
    print(f"  median: {statistics.median(data):.2f}")
    print(f"  mean:   {statistics.mean(data):.2f}")
    print(f"  max:    {max(data):.2f}")
    print(
        f"  stdev:  "
        f"{statistics.stdev(data):.2f}"
        if len(data) > 1
        else "  stdev:  n/a"
    )
PY

echo
printf 'Result directory: %s\n' \
  "${result_dir}"
