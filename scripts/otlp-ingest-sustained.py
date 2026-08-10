#!/usr/bin/env python3

import argparse
import http.client
import json
import math
import secrets
import time
from datetime import datetime, timezone


def percentile(values, percentile_value):
    if not values:
        return 0.0

    ordered = sorted(values)

    rank = math.ceil(
        (percentile_value / 100.0) * len(ordered)
    )

    index = max(
        0,
        min(rank - 1, len(ordered) - 1),
    )

    return ordered[index]


def build_payload(
    span_prefix,
    first_span_number,
    span_count,
):
    now_ns = time.time_ns()

    spans = []

    for offset in range(span_count):
        span_number = first_span_number + offset

        spans.append(
            {
                "traceId": secrets.token_hex(16),
                "spanId": secrets.token_hex(8),
                "name": (
                    f"{span_prefix}"
                    f"{span_number:08d}"
                ),
                "kind": 1,
                "startTimeUnixNano": str(
                    now_ns + offset
                ),
                "endTimeUnixNano": str(
                    now_ns
                    + offset
                    + 1_000_000
                ),
                "status": {
                    "code": 1,
                },
            }
        )

    document = {
        "resourceSpans": [
            {
                "resource": {
                    "attributes": [
                        {
                            "key": "service.name",
                            "value": {
                                "stringValue": (
                                    "aerotrace-sustained-test"
                                ),
                            },
                        }
                    ]
                },
                "scopeSpans": [
                    {
                        "scope": {
                            "name": (
                                "aerotrace.manual."
                                "sustained-test"
                            ),
                        },
                        "spans": spans,
                    }
                ],
            }
        ]
    }

    return json.dumps(
        document,
        separators=(",", ":"),
    ).encode("utf-8")


def new_connection(host, port):
    return http.client.HTTPConnection(
        host,
        port,
        timeout=10,
    )


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Send OTLP spans at a controlled sustained rate."
        )
    )

    parser.add_argument(
        "--target-spans-per-sec",
        type=int,
        default=500,
    )

    parser.add_argument(
        "--duration-sec",
        type=int,
        default=60,
    )

    parser.add_argument(
        "--batch-size",
        type=int,
        default=50,
    )

    parser.add_argument(
        "--host",
        default="127.0.0.1",
    )

    parser.add_argument(
        "--port",
        type=int,
        default=4318,
    )

    parser.add_argument(
        "--endpoint",
        default="/v1/traces",
    )

    args = parser.parse_args()

    if args.target_spans_per_sec <= 0:
        raise SystemExit(
            "--target-spans-per-sec must be positive"
        )

    if args.duration_sec <= 0:
        raise SystemExit(
            "--duration-sec must be positive"
        )

    if args.batch_size <= 0:
        raise SystemExit(
            "--batch-size must be positive"
        )

    total_spans = (
        args.target_spans_per_sec
        * args.duration_sec
    )

    run_id = (
        datetime
        .now(timezone.utc)
        .strftime("%Y%m%dT%H%M%SZ")
        + "-"
        + secrets.token_hex(3)
    )

    span_prefix = (
        f"aerotrace-sustained-{run_id}-"
    )

    print(f"Run ID:                 {run_id}")
    print(f"Span prefix:            {span_prefix}")
    print(
        "Target spans/sec:       "
        f"{args.target_spans_per_sec}"
    )
    print(
        f"Duration sec:            "
        f"{args.duration_sec}"
    )
    print(
        f"Batch size:              "
        f"{args.batch_size}"
    )
    print(
        f"Requested total spans:   "
        f"{total_spans}"
    )

    print()
    print("===== Sending sustained OTLP load =====")

    connection = new_connection(
        args.host,
        args.port,
    )

    benchmark_start = time.perf_counter()

    requested_spans = 0
    accepted_spans = 0

    requested_requests = 0
    accepted_requests = 0
    failed_requests = 0

    latencies_ms = []
    schedule_lags_ms = []

    try:
        while requested_spans < total_spans:
            batch_count = min(
                args.batch_size,
                total_spans - requested_spans,
            )

            scheduled_elapsed = (
                requested_spans
                / args.target_spans_per_sec
            )

            scheduled_at = (
                benchmark_start
                + scheduled_elapsed
            )

            now = time.perf_counter()

            if now < scheduled_at:
                time.sleep(
                    scheduled_at - now
                )

            actual_start = time.perf_counter()

            schedule_lag_ms = max(
                0.0,
                (
                    actual_start
                    - scheduled_at
                )
                * 1000.0,
            )

            schedule_lags_ms.append(
                schedule_lag_ms
            )

            first_span_number = (
                requested_spans + 1
            )

            payload = build_payload(
                span_prefix,
                first_span_number,
                batch_count,
            )

            requested_requests += 1
            requested_spans += batch_count

            request_start = time.perf_counter()

            try:
                connection.request(
                    "POST",
                    args.endpoint,
                    body=payload,
                    headers={
                        "Content-Type": (
                            "application/json"
                        ),
                    },
                )

                response = connection.getresponse()
                response.read()

                request_elapsed = (
                    time.perf_counter()
                    - request_start
                )

                latencies_ms.append(
                    request_elapsed * 1000.0
                )

                if response.status == 200:
                    accepted_requests += 1
                    accepted_spans += batch_count
                else:
                    failed_requests += 1

                    print(
                        "FAILED "
                        f"request={requested_requests} "
                        f"status={response.status}"
                    )

            except Exception as exc:
                request_elapsed = (
                    time.perf_counter()
                    - request_start
                )

                latencies_ms.append(
                    request_elapsed * 1000.0
                )

                failed_requests += 1

                print(
                    "FAILED "
                    f"request={requested_requests} "
                    f"error={exc!r}"
                )

                connection.close()

                connection = new_connection(
                    args.host,
                    args.port,
                )

    finally:
        connection.close()

    nominal_end = (
        benchmark_start
        + args.duration_sec
    )

    now = time.perf_counter()

    if now < nominal_end:
        time.sleep(
            nominal_end - now
        )

    benchmark_end = time.perf_counter()

    elapsed = (
        benchmark_end
        - benchmark_start
    )

    observed_rate = (
        accepted_spans / elapsed
        if elapsed > 0
        else 0.0
    )

    print()
    print("===== RESULT =====")

    print(
        f"Span prefix:             "
        f"{span_prefix}"
    )

    print(
        f"Requested spans:         "
        f"{requested_spans}"
    )

    print(
        f"Accepted spans:          "
        f"{accepted_spans}"
    )

    print(
        f"Requested requests:      "
        f"{requested_requests}"
    )

    print(
        f"Accepted requests:       "
        f"{accepted_requests}"
    )

    print(
        f"Failed requests:         "
        f"{failed_requests}"
    )

    print(
        f"Actual elapsed sec:      "
        f"{elapsed:.6f}"
    )

    print(
        f"Target spans/sec:        "
        f"{args.target_spans_per_sec}"
    )

    print(
        f"Observed accepted spans/sec: "
        f"{observed_rate:.2f}"
    )

    print(
        "Request latency p50 ms: "
        f"{percentile(latencies_ms, 50):.3f}"
    )

    print(
        "Request latency p95 ms: "
        f"{percentile(latencies_ms, 95):.3f}"
    )

    print(
        "Request latency p99 ms: "
        f"{percentile(latencies_ms, 99):.3f}"
    )

    print(
        "Request latency max ms: "
        f"{max(latencies_ms, default=0.0):.3f}"
    )

    print(
        "Schedule lag p50 ms:    "
        f"{percentile(schedule_lags_ms, 50):.3f}"
    )

    print(
        "Schedule lag p95 ms:    "
        f"{percentile(schedule_lags_ms, 95):.3f}"
    )

    print(
        "Schedule lag p99 ms:    "
        f"{percentile(schedule_lags_ms, 99):.3f}"
    )

    print(
        "Schedule lag max ms:    "
        f"{max(schedule_lags_ms, default=0.0):.3f}"
    )

    if accepted_spans != total_spans:
        raise SystemExit(20)

    if failed_requests != 0:
        raise SystemExit(21)

    print()
    print("Sustained OTLP sender: PASS")


if __name__ == "__main__":
    main()
