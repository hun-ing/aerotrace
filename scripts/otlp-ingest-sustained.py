#!/usr/bin/env python3

import argparse
import http.client
import json
import math
import queue
import secrets
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone


DEFAULT_WORKERS = 4
DEFAULT_QUEUE_CAPACITY = 32
DEFAULT_MAX_RATE_ERROR_PCT = 1.0
DEFAULT_MAX_P99_LAG_INTERVALS = 2.0


@dataclass(frozen=True)
class LoadTask:
    request_number: int
    first_span_number: int
    span_count: int
    scheduled_at: float


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


def sender_worker(
    worker_number,
    task_queue,
    span_prefix,
    host,
    port,
    endpoint,
    stats,
    stats_lock,
):
    connection = new_connection(
        host,
        port,
    )

    try:
        while True:
            task = task_queue.get()

            try:
                if task is None:
                    return

                payload = build_payload(
                    span_prefix,
                    task.first_span_number,
                    task.span_count,
                )

                request_start = time.perf_counter()

                send_start_lag_ms = max(
                    0.0,
                    (
                        request_start
                        - task.scheduled_at
                    )
                    * 1000.0,
                )

                try:
                    connection.request(
                        "POST",
                        endpoint,
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

                    with stats_lock:
                        stats["latencies_ms"].append(
                            request_elapsed * 1000.0
                        )

                        stats[
                            "send_start_lags_ms"
                        ].append(
                            send_start_lag_ms
                        )

                        if response.status == 200:
                            stats[
                                "accepted_requests"
                            ] += 1

                            stats[
                                "accepted_spans"
                            ] += task.span_count

                        else:
                            stats[
                                "failed_requests"
                            ] += 1

                            print(
                                "FAILED "
                                f"worker={worker_number} "
                                f"request="
                                f"{task.request_number} "
                                f"status={response.status}"
                            )

                except Exception as exc:
                    request_elapsed = (
                        time.perf_counter()
                        - request_start
                    )

                    with stats_lock:
                        stats["latencies_ms"].append(
                            request_elapsed * 1000.0
                        )

                        stats[
                            "send_start_lags_ms"
                        ].append(
                            send_start_lag_ms
                        )

                        stats[
                            "failed_requests"
                        ] += 1

                        print(
                            "FAILED "
                            f"worker={worker_number} "
                            f"request="
                            f"{task.request_number} "
                            f"error={exc!r}"
                        )

                    connection.close()

                    connection = new_connection(
                        host,
                        port,
                    )

            finally:
                task_queue.task_done()

    finally:
        connection.close()


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
        "--workers",
        type=int,
        default=DEFAULT_WORKERS,
    )

    parser.add_argument(
        "--queue-capacity",
        type=int,
        default=DEFAULT_QUEUE_CAPACITY,
    )

    parser.add_argument(
        "--max-rate-error-pct",
        type=float,
        default=DEFAULT_MAX_RATE_ERROR_PCT,
    )

    parser.add_argument(
        "--max-p99-lag-intervals",
        type=float,
        default=DEFAULT_MAX_P99_LAG_INTERVALS,
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

    if args.workers <= 0:
        raise SystemExit(
            "--workers must be positive"
        )

    if args.queue_capacity <= 0:
        raise SystemExit(
            "--queue-capacity must be positive"
        )

    if args.max_rate_error_pct < 0:
        raise SystemExit(
            "--max-rate-error-pct must not be negative"
        )

    if args.max_p99_lag_intervals <= 0:
        raise SystemExit(
            "--max-p99-lag-intervals must be positive"
        )

    total_spans = (
        args.target_spans_per_sec
        * args.duration_sec
    )

    request_interval_ms = (
        args.batch_size
        / args.target_spans_per_sec
        * 1000.0
    )

    max_p99_lag_ms = (
        request_interval_ms
        * args.max_p99_lag_intervals
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
        "Duration sec:            "
        f"{args.duration_sec}"
    )

    print(
        "Batch size:              "
        f"{args.batch_size}"
    )

    print(
        "Workers:                 "
        f"{args.workers}"
    )

    print(
        "Sender queue capacity:   "
        f"{args.queue_capacity}"
    )

    print(
        "Request interval ms:     "
        f"{request_interval_ms:.3f}"
    )

    print(
        "Max rate error pct:      "
        f"{args.max_rate_error_pct:.3f}"
    )

    print(
        "Max p99 lag ms:          "
        f"{max_p99_lag_ms:.3f}"
    )

    print(
        "Requested total spans:   "
        f"{total_spans}"
    )

    print()
    print("===== Sending sustained OTLP load =====")

    task_queue = queue.Queue(
        maxsize=args.queue_capacity
    )

    stats_lock = threading.Lock()

    stats = {
        "accepted_spans": 0,
        "accepted_requests": 0,
        "failed_requests": 0,
        "latencies_ms": [],
        "producer_lags_ms": [],
        "send_start_lags_ms": [],
        "producer_backpressure_events": 0,
    }

    workers = []

    for worker_number in range(
        1,
        args.workers + 1,
    ):
        worker = threading.Thread(
            target=sender_worker,
            args=(
                worker_number,
                task_queue,
                span_prefix,
                args.host,
                args.port,
                args.endpoint,
                stats,
                stats_lock,
            ),
            name=f"otlp-sender-{worker_number}",
        )

        worker.start()
        workers.append(worker)

    benchmark_start = time.perf_counter()

    requested_spans = 0
    requested_requests = 0

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

            request_number = (
                requested_requests + 1
            )

            first_span_number = (
                requested_spans + 1
            )

            task = LoadTask(
                request_number=request_number,
                first_span_number=first_span_number,
                span_count=batch_count,
                scheduled_at=scheduled_at,
            )

            try:
                task_queue.put_nowait(task)

            except queue.Full:
                with stats_lock:
                    stats[
                        "producer_backpressure_events"
                    ] += 1

                task_queue.put(task)

            enqueued_at = time.perf_counter()

            producer_lag_ms = max(
                0.0,
                (
                    enqueued_at
                    - scheduled_at
                )
                * 1000.0,
            )

            with stats_lock:
                stats[
                    "producer_lags_ms"
                ].append(
                    producer_lag_ms
                )

            requested_requests += 1
            requested_spans += batch_count

    finally:
        for _ in workers:
            task_queue.put(None)

        task_queue.join()

        for worker in workers:
            worker.join()

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

    accepted_spans = stats["accepted_spans"]
    accepted_requests = stats["accepted_requests"]
    failed_requests = stats["failed_requests"]

    latencies_ms = stats["latencies_ms"]

    producer_lags_ms = stats[
        "producer_lags_ms"
    ]

    send_start_lags_ms = stats[
        "send_start_lags_ms"
    ]

    producer_backpressure_events = stats[
        "producer_backpressure_events"
    ]

    observed_rate = (
        accepted_spans / elapsed
        if elapsed > 0
        else 0.0
    )

    rate_error_pct = (
        abs(
            observed_rate
            - args.target_spans_per_sec
        )
        / args.target_spans_per_sec
        * 100.0
    )

    producer_lag_p99 = percentile(
        producer_lags_ms,
        99,
    )

    send_start_lag_p99 = percentile(
        send_start_lags_ms,
        99,
    )

    delivery_success = (
        accepted_spans == total_spans
        and failed_requests == 0
    )

    sustained_rate_valid = (
        rate_error_pct
        <= args.max_rate_error_pct
        and producer_lag_p99
        <= max_p99_lag_ms
        and send_start_lag_p99
        <= max_p99_lag_ms
        and producer_backpressure_events == 0
    )

    print()
    print("===== RESULT =====")

    print(
        "Span prefix:             "
        f"{span_prefix}"
    )

    print(
        "Requested spans:         "
        f"{requested_spans}"
    )

    print(
        "Accepted spans:          "
        f"{accepted_spans}"
    )

    print(
        "Requested requests:      "
        f"{requested_requests}"
    )

    print(
        "Accepted requests:       "
        f"{accepted_requests}"
    )

    print(
        "Failed requests:         "
        f"{failed_requests}"
    )

    print(
        "Actual elapsed sec:      "
        f"{elapsed:.6f}"
    )

    print(
        "Target spans/sec:        "
        f"{args.target_spans_per_sec}"
    )

    print(
        "Observed accepted spans/sec: "
        f"{observed_rate:.2f}"
    )

    print(
        "Rate error pct:          "
        f"{rate_error_pct:.3f}"
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
        "Producer lag p50 ms:    "
        f"{percentile(producer_lags_ms, 50):.3f}"
    )

    print(
        "Producer lag p95 ms:    "
        f"{percentile(producer_lags_ms, 95):.3f}"
    )

    print(
        "Producer lag p99 ms:    "
        f"{producer_lag_p99:.3f}"
    )

    print(
        "Producer lag max ms:    "
        f"{max(producer_lags_ms, default=0.0):.3f}"
    )

    print(
        "Send-start lag p50 ms:  "
        f"{percentile(send_start_lags_ms, 50):.3f}"
    )

    print(
        "Send-start lag p95 ms:  "
        f"{percentile(send_start_lags_ms, 95):.3f}"
    )

    print(
        "Send-start lag p99 ms:  "
        f"{send_start_lag_p99:.3f}"
    )

    print(
        "Send-start lag max ms:  "
        f"{max(send_start_lags_ms, default=0.0):.3f}"
    )

    print(
        "Producer backpressure events: "
        f"{producer_backpressure_events}"
    )

    print(
        "Delivery success:       "
        f"{'PASS' if delivery_success else 'FAIL'}"
    )

    print(
        "Sustained-rate validity: "
        f"{'PASS' if sustained_rate_valid else 'FAIL'}"
    )

    if not delivery_success:
        raise SystemExit(20)

    if not sustained_rate_valid:
        raise SystemExit(22)

    print()
    print("Sustained OTLP sender: PASS")


if __name__ == "__main__":
    main()