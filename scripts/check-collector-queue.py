#!/usr/bin/env python3

from __future__ import annotations

import argparse
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path


METRIC_LINE_RE = re.compile(
    r'^(?P<name>[a-zA-Z_:][a-zA-Z0-9_:]*)'
    r'(?:\{(?P<labels>.*)\})?'
    r'\s+'
    r'(?P<value>[^\s]+)'
    r'(?:\s+\d+)?$'
)

LABEL_RE = re.compile(
    r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:\\.|[^"])*)"'
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Check AeroTrace OpenTelemetry Collector exporter queue state."
        )
    )

    parser.add_argument(
        "--metrics-url",
        default="http://127.0.0.1:8888/metrics",
        help="Collector Prometheus metrics endpoint.",
    )

    parser.add_argument(
        "--config",
        default="otel-collector-config.yaml",
        help=(
            "Collector config used to discover sending_queue.queue_size."
        ),
    )

    parser.add_argument(
        "--queue-capacity",
        type=int,
        default=None,
        help=(
            "Override queue capacity instead of reading it from config."
        ),
    )

    parser.add_argument(
        "--warn-ratio",
        type=float,
        default=0.50,
        help="Warning threshold as queue utilization ratio.",
    )

    parser.add_argument(
        "--critical-ratio",
        type=float,
        default=0.80,
        help="Critical threshold as queue utilization ratio.",
    )

    parser.add_argument(
        "--reference-spans-per-sec",
        type=float,
        default=3250.0,
        help=(
            "Reference ingress rate used to estimate full-outage "
            "queue headroom."
        ),
    )

    return parser.parse_args()


def parse_labels(raw_labels: str | None) -> dict[str, str]:
    if not raw_labels:
        return {}

    labels: dict[str, str] = {}

    for match in LABEL_RE.finditer(raw_labels):
        key, value = match.groups()

        value = (
            value
            .replace(r"\\", "\\")
            .replace(r"\"", '"')
            .replace(r"\n", "\n")
        )

        labels[key] = value

    return labels


def parse_metrics(text: str) -> list[tuple[str, dict[str, str], float]]:
    metrics: list[tuple[str, dict[str, str], float]] = []

    for raw_line in text.splitlines():
        line = raw_line.strip()

        if not line or line.startswith("#"):
            continue

        match = METRIC_LINE_RE.match(line)

        if not match:
            continue

        try:
            value = float(match.group("value"))
        except ValueError:
            continue

        metrics.append(
            (
                match.group("name"),
                parse_labels(match.group("labels")),
                value,
            )
        )

    return metrics


def find_metric(
    metrics: list[tuple[str, dict[str, str], float]],
    name: str,
    required_labels: dict[str, str],
) -> float | None:
    matches: list[float] = []

    for metric_name, labels, value in metrics:
        if metric_name != name:
            continue

        if all(
            labels.get(key) == expected
            for key, expected in required_labels.items()
        ):
            matches.append(value)

    if not matches:
        return None

    if len(matches) > 1:
        raise RuntimeError(
            f"multiple metric series matched {name}: {len(matches)}"
        )

    return matches[0]


def read_queue_capacity(
    config_path: Path,
    explicit_capacity: int | None,
) -> int:
    if explicit_capacity is not None:
        if explicit_capacity <= 0:
            raise ValueError(
                "--queue-capacity must be greater than zero."
            )

        return explicit_capacity

    text = config_path.read_text()

    matches = re.findall(
        r"(?m)^\s*queue_size:\s*(\d+)\s*$",
        text,
    )

    if len(matches) != 1:
        raise RuntimeError(
            "expected exactly one queue_size in "
            f"{config_path}, found {len(matches)}"
        )

    capacity = int(matches[0])

    if capacity <= 0:
        raise RuntimeError(
            f"invalid queue capacity: {capacity}"
        )

    return capacity


def scrape(url: str) -> str:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "aerotrace-collector-check/1.0",
        },
    )

    with urllib.request.urlopen(
        request,
        timeout=5,
    ) as response:
        return response.read().decode(
            "utf-8",
            errors="replace",
        )


def display_number(value: float | None) -> str:
    if value is None:
        return "N/A"

    if value.is_integer():
        return str(int(value))

    return f"{value:.3f}"


def main() -> int:
    args = parse_args()

    if not 0 <= args.warn_ratio <= 1:
        print(
            "UNKNOWN: --warn-ratio must be between 0 and 1.",
            file=sys.stderr,
        )
        return 3

    if not 0 <= args.critical_ratio <= 1:
        print(
            "UNKNOWN: --critical-ratio must be between 0 and 1.",
            file=sys.stderr,
        )
        return 3

    if args.warn_ratio >= args.critical_ratio:
        print(
            "UNKNOWN: warning ratio must be lower than "
            "critical ratio.",
            file=sys.stderr,
        )
        return 3

    if args.reference_spans_per_sec <= 0:
        print(
            "UNKNOWN: reference spans/sec must be greater "
            "than zero.",
            file=sys.stderr,
        )
        return 3

    try:
        queue_capacity = read_queue_capacity(
            Path(args.config),
            args.queue_capacity,
        )

        metrics_text = scrape(args.metrics_url)
        metrics = parse_metrics(metrics_text)

        exporter_labels = {
            "exporter": "otlp_http/aerotrace",
        }

        queue_labels = {
            "data_type": "traces",
            "exporter": "otlp_http/aerotrace",
        }

        receiver_labels = {
            "receiver": "otlp",
            "transport": "http",
        }

        queue_size = find_metric(
            metrics,
            "otelcol_exporter_queue_size",
            queue_labels,
        )

        in_flight = find_metric(
            metrics,
            "otelcol_exporter_in_flight_requests",
            queue_labels,
        )

        sent_spans = find_metric(
            metrics,
            "otelcol_exporter_sent_spans",
            exporter_labels,
        )

        enqueue_failed = find_metric(
            metrics,
            "otelcol_exporter_enqueue_failed_spans",
            exporter_labels,
        )

        send_failed = find_metric(
            metrics,
            "otelcol_exporter_send_failed_spans",
            exporter_labels,
        )

        accepted_spans = find_metric(
            metrics,
            "otelcol_receiver_accepted_spans",
            receiver_labels,
        )

        refused_spans = find_metric(
            metrics,
            "otelcol_receiver_refused_spans",
            receiver_labels,
        )

    except (
        OSError,
        RuntimeError,
        ValueError,
        urllib.error.URLError,
    ) as exc:
        print(
            f"UNKNOWN: {exc}",
            file=sys.stderr,
        )
        return 3

    if queue_size is None:
        print(
            "UNKNOWN: otelcol_exporter_queue_size "
            "series was not found.",
            file=sys.stderr,
        )
        return 3

    utilization = queue_size / queue_capacity

    remaining_items = max(
        0.0,
        queue_capacity - queue_size,
    )

    headroom_sec = (
        remaining_items
        / args.reference_spans_per_sec
    )

    if utilization >= args.critical_ratio:
        status = "CRITICAL"
        exit_code = 2
    elif utilization >= args.warn_ratio:
        status = "WARNING"
        exit_code = 1
    else:
        status = "OK"
        exit_code = 0

    print(f"status={status}")
    print(
        f"queue_size={display_number(queue_size)}"
    )
    print(f"queue_capacity={queue_capacity}")
    print(
        f"queue_utilization_pct={utilization * 100:.2f}"
    )
    print(
        f"queue_remaining_items={int(remaining_items)}"
    )
    print(
        "full_outage_headroom_sec="
        f"{headroom_sec:.2f}"
    )
    print(
        "reference_spans_per_sec="
        f"{args.reference_spans_per_sec:.2f}"
    )

    print(
        f"in_flight={display_number(in_flight)}"
    )

    print(
        f"sent_spans={display_number(sent_spans)}"
    )
    print(
        "enqueue_failed_spans="
        f"{display_number(enqueue_failed)}"
    )
    print(
        f"send_failed_spans={display_number(send_failed)}"
    )

    print(
        f"accepted_spans={display_number(accepted_spans)}"
    )
    print(
        f"refused_spans={display_number(refused_spans)}"
    )

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
