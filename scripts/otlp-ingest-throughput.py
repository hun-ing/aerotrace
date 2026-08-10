#!/usr/bin/env python3

import argparse
import concurrent.futures
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
    index = max(0, min(rank - 1, len(ordered) - 1))

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
                "name": f"{span_prefix}{span_number:06d}",
                "kind": 1,
                "startTimeUnixNano": str(now_ns + offset),
                "endTimeUnixNano": str(
                    now_ns + offset + 1_000_000
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
                                    "aerotrace-throughput-test"
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
                                "throughput-test"
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


def worker(
    host,
    port,
    endpoint,
    jobs,
):
    connection = http.client.HTTPConnection(
        host,
        port,
        timeout=10,
    )

    results = []

    try:
        for request_number, payload, span_count in jobs:
            started = time.perf_counter()

            try:
                connection.request(
                    "POST",
                    endpoint,
                    body=payload,
                    headers={
                        "Content-Type": "application/json",
                    },
                )

                response = connection.getresponse()
                response.read()

                elapsed = time.perf_counter() - started

                results.append(
                    (
                        request_number,
                        response.status,
                        span_count,
                        elapsed,
                        None,
                    )
                )

            except Exception as exc:
                elapsed = time.perf_counter() - started

                results.append(
                    (
                        request_number,
                        0,
                        span_count,
                        elapsed,
                        repr(exc),
                    )
                )

                connection.close()

                connection = http.client.HTTPConnection(
                    host,
                    port,
                    timeout=10,
                )

    finally:
        connection.close()

    return results


def main():
    parser = argparse.ArgumentParser()

    parser.add_argument(
        "--total-spans",
        type=int,
        default=1000,
    )

    parser.add_argument(
        "--batch-size",
        type=int,
        default=50,
    )

    parser.add_argument(
        "--concurrency",
        type=int,
        default=4,
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

    if args.total_spans <= 0:
        raise SystemExit(
            "--total-spans must be positive"
        )

    if args.batch_size <= 0:
        raise SystemExit(
            "--batch-size must be positive"
        )

    if args.concurrency <= 0:
        raise SystemExit(
            "--concurrency must be positive"
        )

    run_id = (
        datetime
        .now(timezone.utc)
        .strftime("%Y%m%dT%H%M%SZ")
        + "-"
        + secrets.token_hex(3)
    )

    span_prefix = (
        f"aerotrace-throughput-{run_id}-"
    )

    request_jobs = []

    span_number = 1
    request_number = 1

    while span_number <= args.total_spans:
        current_batch = min(
            args.batch_size,
            args.total_spans - span_number + 1,
        )

        payload = build_payload(
            span_prefix,
            span_number,
            current_batch,
        )

        request_jobs.append(
            (
                request_number,
                payload,
                current_batch,
            )
        )

        span_number += current_batch
        request_number += 1

    worker_jobs = [
        []
        for _ in range(
            min(args.concurrency, len(request_jobs))
        )
    ]

    for index, job in enumerate(request_jobs):
        worker_jobs[index % len(worker_jobs)].append(job)

    print(f"Run ID:       {run_id}")
    print(f"Span prefix:  {span_prefix}")
    print(f"Total spans:  {args.total_spans}")
    print(f"Batch size:   {args.batch_size}")
    print(f"Requests:     {len(request_jobs)}")
    print(f"Concurrency:  {len(worker_jobs)}")
    print()
    print("===== Sending OTLP spans =====")

    started = time.perf_counter()

    all_results = []

    with concurrent.futures.ThreadPoolExecutor(
        max_workers=len(worker_jobs)
    ) as executor:
        futures = [
            executor.submit(
                worker,
                args.host,
                args.port,
                args.endpoint,
                jobs,
            )
            for jobs in worker_jobs
        ]

        for future in concurrent.futures.as_completed(
            futures
        ):
            all_results.extend(
                future.result()
            )

    elapsed = time.perf_counter() - started

    accepted_requests = 0
    accepted_spans = 0
    failed_requests = 0
    latencies_ms = []

    for (
        request_number,
        status,
        span_count,
        request_elapsed,
        error,
    ) in sorted(all_results):
        latencies_ms.append(
            request_elapsed * 1000.0
        )

        if status == 200:
            accepted_requests += 1
            accepted_spans += span_count
        else:
            failed_requests += 1

            print(
                "FAILED "
                f"request={request_number} "
                f"status={status} "
                f"error={error}"
            )

    spans_per_second = (
        accepted_spans / elapsed
        if elapsed > 0
        else 0.0
    )

    requests_per_second = (
        accepted_requests / elapsed
        if elapsed > 0
        else 0.0
    )

    print()
    print("===== RESULT =====")
    print(f"Span prefix:         {span_prefix}")
    print(f"Requested spans:     {args.total_spans}")
    print(f"Accepted spans:      {accepted_spans}")
    print(f"Requested requests:  {len(request_jobs)}")
    print(f"Accepted requests:   {accepted_requests}")
    print(f"Failed requests:     {failed_requests}")
    print(f"Send elapsed sec:    {elapsed:.6f}")
    print(f"Accepted spans/sec:  {spans_per_second:.2f}")
    print(f"Accepted req/sec:    {requests_per_second:.2f}")
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

    if accepted_spans != args.total_spans:
        raise SystemExit(20)

    if failed_requests != 0:
        raise SystemExit(21)


if __name__ == "__main__":
    main()
