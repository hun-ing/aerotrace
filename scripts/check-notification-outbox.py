#!/usr/bin/env python3

import argparse
import json
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Inspect the AeroTrace notification outbox "
            "and report pending event age and size."
        )
    )

    parser.add_argument(
        "--outbox-dir",
        type=Path,
        required=True,
        help="Directory containing pending notification JSON files.",
    )

    parser.add_argument(
        "--warn-count",
        type=int,
        default=None,
        help=(
            "Return WARNING when pending event count "
            "reaches this value."
        ),
    )

    parser.add_argument(
        "--critical-count",
        type=int,
        default=None,
        help=(
            "Return CRITICAL when pending event count "
            "reaches this value."
        ),
    )

    parser.add_argument(
        "--warn-age-sec",
        type=float,
        default=None,
        help=(
            "Return WARNING when oldest pending event "
            "age reaches this many seconds."
        ),
    )

    parser.add_argument(
        "--critical-age-sec",
        type=float,
        default=None,
        help=(
            "Return CRITICAL when oldest pending event "
            "age reaches this many seconds."
        ),
    )

    return parser.parse_args()


def load_event(
    path: Path,
) -> dict[str, Any]:
    with path.open(
        "r",
        encoding="utf-8",
    ) as file:
        payload = json.load(file)

    if not isinstance(payload, dict):
        raise ValueError(
            f"{path}: event payload must be a JSON object"
        )

    event_id = payload.get(
        "event_id"
    )

    if (
        not isinstance(event_id, str)
        or not event_id
    ):
        raise ValueError(
            f"{path}: event_id must be a non-empty string"
        )

    if path.name != f"{event_id}.json":
        raise ValueError(
            f"{path}: filename does not match event_id"
        )

    evaluated_at = payload.get(
        "evaluated_at"
    )

    if (
        not isinstance(evaluated_at, str)
        or not evaluated_at
    ):
        raise ValueError(
            f"{path}: evaluated_at must be a non-empty string"
        )

    try:
        evaluated_datetime = (
            datetime.fromisoformat(
                evaluated_at
            )
        )
    except ValueError as exc:
        raise ValueError(
            f"{path}: evaluated_at is not valid ISO-8601"
        ) from exc

    if (
        evaluated_datetime.tzinfo
        is None
    ):
        raise ValueError(
            f"{path}: evaluated_at must include a timezone"
        )

    return payload


def validate_thresholds(
    args: argparse.Namespace,
) -> None:
    numeric_thresholds = {
        "--warn-count": args.warn_count,
        "--critical-count": args.critical_count,
        "--warn-age-sec": args.warn_age_sec,
        "--critical-age-sec": args.critical_age_sec,
    }

    for name, value in numeric_thresholds.items():
        if (
            value is not None
            and value <= 0
        ):
            raise ValueError(
                f"{name} must be > 0"
            )

    if (
        args.warn_count is not None
        and args.critical_count is not None
        and args.warn_count
        >= args.critical_count
    ):
        raise ValueError(
            "--warn-count must be lower than "
            "--critical-count"
        )

    if (
        args.warn_age_sec is not None
        and args.critical_age_sec is not None
        and args.warn_age_sec
        >= args.critical_age_sec
    ):
        raise ValueError(
            "--warn-age-sec must be lower than "
            "--critical-age-sec"
        )


def classify_threshold(
    value: float,
    warning: float | None,
    critical: float | None,
) -> str:
    if (
        critical is not None
        and value >= critical
    ):
        return "CRITICAL"

    if (
        warning is not None
        and value >= warning
    ):
        return "WARNING"

    return "OK"


def combine_status(
    count_status: str,
    age_status: str,
) -> str:
    severity = {
        "OK": 0,
        "WARNING": 1,
        "CRITICAL": 2,
    }

    if (
        severity[count_status]
        >= severity[age_status]
    ):
        return count_status

    return age_status


def status_exit_code(
    status: str,
) -> int:
    return {
        "OK": 0,
        "WARNING": 1,
        "CRITICAL": 2,
    }[status]


def print_empty_status() -> None:
    print("status=OK")
    print("pending_events=0")
    print("pending_bytes=0")
    print("oldest_pending_age_sec=N/A")
    print("oldest_event_id=N/A")
    print("oldest_evaluated_at=N/A")


def main() -> int:
    args = parse_args()

    try:
        validate_thresholds(
            args
        )
    except ValueError as exc:
        print("status=UNKNOWN")
        print(
            f"checker_error={exc}"
        )
        return 3

    outbox_dir = args.outbox_dir

    if not outbox_dir.exists():
        print_empty_status()
        return 0

    if not outbox_dir.is_dir():
        print(
            "status=UNKNOWN"
        )
        print(
            "checker_error="
            f"outbox path is not a directory: {outbox_dir}"
        )

        return 3

    pending_files = sorted(
        path
        for path in outbox_dir.glob(
            "*.json"
        )
        if path.is_file()
    )

    if not pending_files:
        print_empty_status()
        return 0

    now_epoch = time.time()

    pending_bytes = 0
    oldest_event_id = None
    oldest_evaluated_at = None
    oldest_age_sec = None

    for path in pending_files:
        try:
            payload = load_event(
                path
            )

            file_size = (
                path.stat().st_size
            )

            evaluated_at = payload[
                "evaluated_at"
            ]

            evaluated_datetime = (
                datetime.fromisoformat(
                    evaluated_at
                )
            )

            age_sec = (
                now_epoch
                - evaluated_datetime.timestamp()
            )
        except (
            OSError,
            ValueError,
            json.JSONDecodeError,
        ) as exc:
            print(
                "status=UNKNOWN"
            )
            print(
                f"pending_events={len(pending_files)}"
            )
            print(
                "checker_error="
                f"invalid pending event: {exc}"
            )

            return 3

        pending_bytes += (
            file_size
        )

        if (
            oldest_age_sec is None
            or age_sec > oldest_age_sec
        ):
            oldest_age_sec = (
                age_sec
            )
            oldest_event_id = (
                payload["event_id"]
            )
            oldest_evaluated_at = (
                evaluated_at
            )

    count_status = classify_threshold(
        float(len(pending_files)),
        (
            float(args.warn_count)
            if args.warn_count is not None
            else None
        ),
        (
            float(args.critical_count)
            if args.critical_count is not None
            else None
        ),
    )

    age_status = classify_threshold(
        oldest_age_sec,
        args.warn_age_sec,
        args.critical_age_sec,
    )

    status = combine_status(
        count_status,
        age_status,
    )

    print(
        f"status={status}"
    )
    print(
        f"pending_events={len(pending_files)}"
    )
    print(
        f"pending_bytes={pending_bytes}"
    )
    print(
        "oldest_pending_age_sec="
        f"{oldest_age_sec:.3f}"
    )
    print(
        "oldest_event_id="
        f"{oldest_event_id}"
    )
    print(
        "oldest_evaluated_at="
        f"{oldest_evaluated_at}"
    )
    print(
        "count_threshold_status="
        f"{count_status}"
    )
    print(
        "age_threshold_status="
        f"{age_status}"
    )

    return status_exit_code(
        status
    )


if __name__ == "__main__":
    sys.exit(
        main()
    )
