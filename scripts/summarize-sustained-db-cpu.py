#!/usr/bin/env python3

import argparse
import csv
import statistics
import sys
from datetime import datetime


def parse_args():
    parser = argparse.ArgumentParser(
        description="Summarize TimescaleDB CPU from sustained-load resources.tsv"
    )
    parser.add_argument(
        "resources_file",
        help="Path to resources.tsv",
    )
    parser.add_argument(
        "--start-sec",
        type=int,
        default=10,
        help="First full-rate sampling slot in seconds (default: 10)",
    )
    parser.add_argument(
        "--end-sec",
        type=int,
        default=60,
        help="Last full-rate sampling slot in seconds (default: 60)",
    )
    parser.add_argument(
        "--interval-sec",
        type=int,
        default=5,
        help="Expected sampling interval in seconds (default: 5)",
    )
    return parser.parse_args()


def parse_timestamp(value):
    return datetime.fromisoformat(
        value.replace("Z", "+00:00")
    )


def normalize_slot(elapsed_sec, interval_sec):
    return int(
        (elapsed_sec + interval_sec / 2)
        // interval_sec
    ) * interval_sec


def parse_cpu(value):
    return float(value.rstrip("%"))


def main():
    args = parse_args()

    if args.start_sec < 0:
        raise SystemExit("--start-sec must be >= 0")

    if args.end_sec < args.start_sec:
        raise SystemExit("--end-sec must be >= --start-sec")

    if args.interval_sec <= 0:
        raise SystemExit("--interval-sec must be > 0")

    if (
        (args.end_sec - args.start_sec)
        % args.interval_sec
        != 0
    ):
        raise SystemExit(
            "start/end range must align with interval"
        )

    with open(
        args.resources_file,
        encoding="utf-8",
        newline="",
    ) as f:
        rows = list(
            csv.DictReader(f, delimiter="\t")
        )

    if not rows:
        raise SystemExit("resources file is empty")

    required_columns = {
        "timestamp_utc",
        "db_cpu",
    }

    missing_columns = (
        required_columns - set(rows[0].keys())
    )

    if missing_columns:
        raise SystemExit(
            "missing columns: "
            + ", ".join(sorted(missing_columns))
        )

    first_timestamp = parse_timestamp(
        rows[0]["timestamp_utc"]
    )

    samples_by_slot = {}

    for row in rows:
        timestamp = parse_timestamp(
            row["timestamp_utc"]
        )

        elapsed_sec = (
            timestamp - first_timestamp
        ).total_seconds()

        slot = normalize_slot(
            elapsed_sec,
            args.interval_sec,
        )

        if not (
            args.start_sec
            <= slot
            <= args.end_sec
        ):
            continue

        if slot in samples_by_slot:
            raise SystemExit(
                f"duplicate sample for slot t={slot}"
            )

        samples_by_slot[slot] = parse_cpu(
            row["db_cpu"]
        )

    expected_slots = list(
        range(
            args.start_sec,
            args.end_sec + 1,
            args.interval_sec,
        )
    )

    missing_slots = [
        slot
        for slot in expected_slots
        if slot not in samples_by_slot
    ]

    if missing_slots:
        print(
            "missing full-rate sampling slots: "
            + ", ".join(
                f"t={slot}"
                for slot in missing_slots
            ),
            file=sys.stderr,
        )
        return 2

    values = [
        samples_by_slot[slot]
        for slot in expected_slots
    ]

    print(
        "full_rate_slots="
        + ",".join(
            str(slot)
            for slot in expected_slots
        )
    )
    print(
        f"full_rate_samples={len(values)}"
    )
    print(
        f"db_cpu_avg={statistics.mean(values):.2f}%"
    )
    print(
        f"db_cpu_median={statistics.median(values):.2f}%"
    )
    print(
        f"db_cpu_min={min(values):.2f}%"
    )
    print(
        f"db_cpu_max={max(values):.2f}%"
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
