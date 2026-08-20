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
            "Inspect the current AeroTrace webhook "
            "transport failure state."
        )
    )

    parser.add_argument(
        "--failure-state-file",
        type=Path,
        required=True,
        help=(
            "Persistent webhook transport failure "
            "state JSON file."
        ),
    )

    parser.add_argument(
        "--warn-count",
        type=int,
        default=None,
        help=(
            "Return WARNING when consecutive failure "
            "count reaches this value."
        ),
    )

    parser.add_argument(
        "--critical-count",
        type=int,
        default=None,
        help=(
            "Return CRITICAL when consecutive failure "
            "count reaches this value."
        ),
    )

    parser.add_argument(
        "--warn-duration-sec",
        type=float,
        default=None,
        help=(
            "Return WARNING when transport failure "
            "duration reaches this many seconds."
        ),
    )

    parser.add_argument(
        "--critical-duration-sec",
        type=float,
        default=None,
        help=(
            "Return CRITICAL when transport failure "
            "duration reaches this many seconds."
        ),
    )

    return parser.parse_args()


def load_state(
    path: Path,
) -> dict[str, Any]:
    with path.open(
        "r",
        encoding="utf-8",
    ) as file:
        state = json.load(
            file
        )

    if not isinstance(
        state,
        dict,
    ):
        raise ValueError(
            f"{path}: failure state must be a JSON object"
        )

    return state


def parse_timestamp(
    path: Path,
    state: dict[str, Any],
    key: str,
) -> datetime:
    value = state.get(
        key
    )

    if (
        not isinstance(value, str)
        or not value
    ):
        raise ValueError(
            f"{path}: {key} must be "
            "a non-empty string"
        )

    try:
        parsed = datetime.fromisoformat(
            value
        )
    except ValueError as exc:
        raise ValueError(
            f"{path}: {key} is not valid ISO-8601"
        ) from exc

    if parsed.tzinfo is None:
        raise ValueError(
            f"{path}: {key} must include a timezone"
        )

    return parsed


def validate_state(
    path: Path,
    state: dict[str, Any],
) -> tuple[datetime, datetime]:
    if state.get(
        "failure_state_schema_version"
    ) != 1:
        raise ValueError(
            f"{path}: unsupported "
            "failure_state_schema_version"
        )

    if state.get(
        "transport"
    ) != "webhook":
        raise ValueError(
            f"{path}: transport must be webhook"
        )

    failed_event_id = state.get(
        "failed_event_id"
    )

    if (
        not isinstance(failed_event_id, str)
        or not failed_event_id
    ):
        raise ValueError(
            f"{path}: failed_event_id must be "
            "a non-empty string"
        )

    failure_kind = state.get(
        "failure_kind"
    )

    if failure_kind not in {
        "retryable",
        "permanent",
    }:
        raise ValueError(
            f"{path}: invalid failure_kind"
        )

    failure_reason = state.get(
        "failure_reason"
    )

    if (
        not isinstance(failure_reason, str)
        or not failure_reason
    ):
        raise ValueError(
            f"{path}: failure_reason must be "
            "a non-empty string"
        )

    failure_count = state.get(
        "failure_count"
    )

    if (
        not isinstance(failure_count, int)
        or isinstance(failure_count, bool)
        or failure_count <= 0
    ):
        raise ValueError(
            f"{path}: failure_count must be "
            "a positive integer"
        )

    first_failed_at = parse_timestamp(
        path,
        state,
        "first_failed_at",
    )

    last_failed_at = parse_timestamp(
        path,
        state,
        "last_failed_at",
    )

    if (
        last_failed_at
        < first_failed_at
    ):
        raise ValueError(
            f"{path}: last_failed_at is earlier "
            "than first_failed_at"
        )

    return (
        first_failed_at,
        last_failed_at,
    )


def validate_thresholds(
    args: argparse.Namespace,
) -> None:
    thresholds = {
        "--warn-count": args.warn_count,
        "--critical-count": args.critical_count,
        "--warn-duration-sec": args.warn_duration_sec,
        "--critical-duration-sec": args.critical_duration_sec,
    }

    for name, value in thresholds.items():
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
        args.warn_duration_sec is not None
        and args.critical_duration_sec is not None
        and args.warn_duration_sec
        >= args.critical_duration_sec
    ):
        raise ValueError(
            "--warn-duration-sec must be lower than "
            "--critical-duration-sec"
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
    *statuses: str,
) -> str:
    severity = {
        "OK": 0,
        "WARNING": 1,
        "CRITICAL": 2,
    }

    return max(
        statuses,
        key=severity.__getitem__,
    )


def status_exit_code(
    status: str,
) -> int:
    return {
        "OK": 0,
        "WARNING": 1,
        "CRITICAL": 2,
    }[status]


def print_inactive() -> None:
    print("status=OK")
    print("active_failure=false")
    print("failure_kind_status=OK")
    print("count_threshold_status=OK")
    print("duration_threshold_status=OK")
    print("transport=N/A")
    print("failed_event_id=N/A")
    print("failure_kind=N/A")
    print("failure_reason=N/A")
    print("failure_count=0")
    print("failure_duration_sec=N/A")
    print("last_failure_age_sec=N/A")
    print("first_failed_at=N/A")
    print("last_failed_at=N/A")


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

    state_path = (
        args.failure_state_file
    )

    if not state_path.exists():
        print_inactive()
        return 0

    if not state_path.is_file():
        print("status=UNKNOWN")
        print(
            "checker_error="
            "failure state path is not a file: "
            f"{state_path}"
        )
        return 3

    try:
        state = load_state(
            state_path
        )

        (
            first_failed_at,
            last_failed_at,
        ) = validate_state(
            state_path,
            state,
        )

        now_epoch = time.time()

        failure_duration_sec = (
            now_epoch
            - first_failed_at.timestamp()
        )

        last_failure_age_sec = (
            now_epoch
            - last_failed_at.timestamp()
        )

        if failure_duration_sec < 0:
            raise ValueError(
                f"{state_path}: first_failed_at "
                "is in the future"
            )

        if last_failure_age_sec < 0:
            raise ValueError(
                f"{state_path}: last_failed_at "
                "is in the future"
            )
    except (
        OSError,
        ValueError,
        json.JSONDecodeError,
    ) as exc:
        print("status=UNKNOWN")
        print(
            "checker_error="
            f"invalid failure state: {exc}"
        )
        return 3

    failure_kind_status = (
        "CRITICAL"
        if state["failure_kind"] == "permanent"
        else "OK"
    )

    count_threshold_status = (
        classify_threshold(
            float(
                state["failure_count"]
            ),
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
    )

    duration_threshold_status = (
        classify_threshold(
            failure_duration_sec,
            args.warn_duration_sec,
            args.critical_duration_sec,
        )
    )

    status = combine_status(
        failure_kind_status,
        count_threshold_status,
        duration_threshold_status,
    )

    print(
        f"status={status}"
    )
    print("active_failure=true")
    print(
        "failure_kind_status="
        f"{failure_kind_status}"
    )
    print(
        "count_threshold_status="
        f"{count_threshold_status}"
    )
    print(
        "duration_threshold_status="
        f"{duration_threshold_status}"
    )
    print(
        "transport="
        f"{state['transport']}"
    )
    print(
        "failed_event_id="
        f"{state['failed_event_id']}"
    )
    print(
        "failure_kind="
        f"{state['failure_kind']}"
    )
    print(
        "failure_reason="
        f"{state['failure_reason']}"
    )
    print(
        "failure_count="
        f"{state['failure_count']}"
    )
    print(
        "failure_duration_sec="
        f"{failure_duration_sec:.3f}"
    )
    print(
        "last_failure_age_sec="
        f"{last_failure_age_sec:.3f}"
    )
    print(
        "first_failed_at="
        f"{state['first_failed_at']}"
    )
    print(
        "last_failed_at="
        f"{state['last_failed_at']}"
    )

    return status_exit_code(
        status
    )


if __name__ == "__main__":
    sys.exit(
        main()
    )
