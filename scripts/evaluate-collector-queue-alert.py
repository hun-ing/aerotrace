#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


VALID_STATUSES = {
    "OK",
    "WARNING",
    "CRITICAL",
    "UNKNOWN",
}

EXIT_CODE_TO_STATUS = {
    0: "OK",
    1: "WARNING",
    2: "CRITICAL",
    3: "UNKNOWN",
}


def default_checker_path() -> Path:
    return Path(__file__).resolve().with_name(
        "check-collector-queue.py"
    )


def default_state_path() -> Path:
    xdg_state_home = os.environ.get("XDG_STATE_HOME")

    if xdg_state_home:
        base = Path(xdg_state_home)
    else:
        base = Path.home() / ".local" / "state"

    return (
        base
        / "aerotrace"
        / "collector-queue-alert.json"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Evaluate AeroTrace Collector queue alert state "
            "and suppress duplicate notifications."
        )
    )

    parser.add_argument(
        "--checker-path",
        type=Path,
        default=default_checker_path(),
        help="Path to check-collector-queue.py.",
    )

    parser.add_argument(
        "--state-file",
        type=Path,
        default=default_state_path(),
        help="Persistent alert state file.",
    )

    parser.add_argument(
        "--repeat-after-sec",
        type=float,
        default=300.0,
        help=(
            "Repeat a non-OK notification after this many "
            "seconds if the state has not changed."
        ),
    )

    parser.add_argument(
        "--checker-timeout-sec",
        type=float,
        default=10.0,
        help="Maximum runtime for the queue checker.",
    )

    return parser.parse_args()


def utc_now_iso() -> str:
    return datetime.now(
        timezone.utc
    ).isoformat(
        timespec="seconds"
    )


def parse_checker_status(stdout: str) -> str | None:
    for raw_line in stdout.splitlines():
        key, separator, value = raw_line.partition("=")

        if separator != "=":
            continue

        if key.strip() != "status":
            continue

        status = value.strip()

        if status in VALID_STATUSES:
            return status

    return None


def load_state(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}

    with path.open(
        "r",
        encoding="utf-8",
    ) as file:
        value = json.load(file)

    if not isinstance(value, dict):
        raise ValueError(
            f"state file must contain an object: {path}"
        )

    return value


def write_state(
    path: Path,
    state: dict[str, Any],
) -> None:
    path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    temporary = path.with_name(
        f".{path.name}.tmp"
    )

    with temporary.open(
        "w",
        encoding="utf-8",
    ) as file:
        json.dump(
            state,
            file,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )

        file.write("\n")
        file.flush()
        os.fsync(file.fileno())

    temporary.replace(path)


def run_checker(
    checker_path: Path,
    timeout_sec: float,
) -> tuple[
    str,
    int | None,
    str,
    str,
]:
    try:
        result = subprocess.run(
            [str(checker_path)],
            capture_output=True,
            text=True,
            timeout=timeout_sec,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        stdout = exc.stdout or ""
        stderr = exc.stderr or ""

        return (
            "UNKNOWN",
            None,
            stdout,
            (
                stderr
                + f"\nchecker timed out after "
                f"{timeout_sec:.1f} seconds"
            ).strip(),
        )
    except OSError as exc:
        return (
            "UNKNOWN",
            None,
            "",
            f"checker execution failed: {exc}",
        )

    exit_status = EXIT_CODE_TO_STATUS.get(
        result.returncode
    )

    parsed_status = parse_checker_status(
        result.stdout
    )

    if exit_status is None:
        return (
            "UNKNOWN",
            result.returncode,
            result.stdout,
            (
                result.stderr
                + "\nunexpected checker exit code: "
                f"{result.returncode}"
            ).strip(),
        )

    if (
        parsed_status is not None
        and parsed_status != exit_status
    ):
        return (
            "UNKNOWN",
            result.returncode,
            result.stdout,
            (
                result.stderr
                + "\nchecker status/exit-code mismatch: "
                f"status={parsed_status} "
                f"exit={result.returncode}"
            ).strip(),
        )

    return (
        parsed_status or exit_status,
        result.returncode,
        result.stdout,
        result.stderr,
    )


def decide_event(
    previous_status: str | None,
    current_status: str,
    last_notification_epoch: float | None,
    now_epoch: float,
    repeat_after_sec: float,
) -> tuple[str, bool]:
    if previous_status is None:
        if current_status == "OK":
            return "NONE", False

        return "ALERT", True

    if current_status != previous_status:
        if current_status == "OK":
            return "RECOVERY", True

        if previous_status == "OK":
            return "ALERT", True

        return "STATUS_CHANGE", True

    if current_status == "OK":
        return "NONE", False

    if last_notification_epoch is None:
        return "ALERT", True

    elapsed = (
        now_epoch
        - last_notification_epoch
    )

    if elapsed >= repeat_after_sec:
        return "REMINDER", True

    return "NONE", False


def print_checker_output(
    stdout: str,
    stderr: str,
) -> None:
    print("checker_output_begin")

    if stdout.strip():
        print(stdout.rstrip())

    if stderr.strip():
        for line in stderr.rstrip().splitlines():
            print(
                f"checker_stderr={line}"
            )

    print("checker_output_end")


def main() -> int:
    args = parse_args()

    if args.repeat_after_sec < 0:
        print(
            "evaluator_error="
            "--repeat-after-sec must be >= 0",
            file=sys.stderr,
        )
        return 4

    if args.checker_timeout_sec <= 0:
        print(
            "evaluator_error="
            "--checker-timeout-sec must be > 0",
            file=sys.stderr,
        )
        return 4

    try:
        state = load_state(
            args.state_file
        )
    except (
        OSError,
        ValueError,
        json.JSONDecodeError,
    ) as exc:
        print(
            f"evaluator_error={exc}",
            file=sys.stderr,
        )
        return 4

    (
        current_status,
        checker_exit_code,
        checker_stdout,
        checker_stderr,
    ) = run_checker(
        args.checker_path,
        args.checker_timeout_sec,
    )

    previous_status_value = state.get(
        "current_status"
    )

    if previous_status_value in VALID_STATUSES:
        previous_status = previous_status_value
    else:
        previous_status = None

    last_notification_value = state.get(
        "last_notification_epoch"
    )

    if isinstance(
        last_notification_value,
        (int, float),
    ):
        last_notification_epoch = float(
            last_notification_value
        )
    else:
        last_notification_epoch = None

    now_epoch = time.time()
    now_iso = utc_now_iso()

    event, alert_required = decide_event(
        previous_status=previous_status,
        current_status=current_status,
        last_notification_epoch=last_notification_epoch,
        now_epoch=now_epoch,
        repeat_after_sec=args.repeat_after_sec,
    )

    status_changed = (
        previous_status != current_status
    )

    new_state = dict(state)

    new_state["current_status"] = (
        current_status
    )

    new_state["last_evaluated_at"] = (
        now_iso
    )

    if status_changed:
        new_state["last_changed_at"] = (
            now_iso
        )

    if alert_required:
        new_state["last_notification_at"] = (
            now_iso
        )
        new_state["last_notification_epoch"] = (
            now_epoch
        )

    try:
        write_state(
            args.state_file,
            new_state,
        )
    except OSError as exc:
        print(
            f"evaluator_error={exc}",
            file=sys.stderr,
        )
        return 4

    previous_display = (
        previous_status
        if previous_status is not None
        else "NONE"
    )

    checker_exit_display = (
        str(checker_exit_code)
        if checker_exit_code is not None
        else "N/A"
    )

    print(
        f"event={event}"
    )
    print(
        "alert_required="
        f"{str(alert_required).lower()}"
    )
    print(
        f"previous_status={previous_display}"
    )
    print(
        f"current_status={current_status}"
    )
    print(
        f"checker_exit_code={checker_exit_display}"
    )
    print(
        f"state_file={args.state_file}"
    )

    print_checker_output(
        checker_stdout,
        checker_stderr,
    )

    return 0


if __name__ == "__main__":
    sys.exit(main())
