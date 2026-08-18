#!/usr/bin/env python3

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit


VALID_EVENTS = {
    "ALERT",
    "STATUS_CHANGE",
    "RECOVERY",
    "REMINDER",
}

VALID_STATUSES = {
    "OK",
    "WARNING",
    "CRITICAL",
    "UNKNOWN",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Process AeroTrace notification outbox events "
            "in order."
        )
    )

    parser.add_argument(
        "--outbox-dir",
        type=Path,
        required=True,
        help="Directory containing pending event JSON files.",
    )

    parser.add_argument(
        "--receipt-dir",
        "--delivered-dir",
        dest="receipt_dir",
        type=Path,
        required=True,
        help=(
            "Directory containing successful delivery receipts. "
            "--delivered-dir is retained as a compatibility alias."
        ),
    )

    parser.add_argument(
        "--transport",
        choices=("local-file", "webhook"),
        default="local-file",
        help=(
            "Notification transport. "
            "Defaults to local-file."
        ),
    )

    parser.add_argument(
        "--webhook-url",
        default=None,
        help=(
            "Webhook URL for local/manual testing. "
            "For deployment, prefer the "
            "AEROTRACE_WEBHOOK_URL environment variable."
        ),
    )

    parser.add_argument(
        "--webhook-timeout-sec",
        type=float,
        default=5.0,
        help=(
            "Webhook request timeout in seconds. "
            "Defaults to 5."
        ),
    )

    parser.add_argument(
        "--max-events",
        type=int,
        default=100,
        help="Maximum number of pending events to process.",
    )

    parser.add_argument(
        "--quiet-idle",
        action="store_true",
        help=(
            "Produce no output when no pending events exist."
        ),
    )

    return parser.parse_args()


def fsync_directory(path: Path) -> None:
    flags = os.O_RDONLY

    if hasattr(os, "O_DIRECTORY"):
        flags |= os.O_DIRECTORY

    fd = os.open(
        path,
        flags,
    )

    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def load_json(path: Path) -> dict[str, Any]:
    with path.open(
        "r",
        encoding="utf-8",
    ) as file:
        payload = json.load(file)

    if not isinstance(payload, dict):
        raise ValueError(
            f"{path}: event payload must be a JSON object"
        )

    return payload


def validate_event(
    path: Path,
    payload: dict[str, Any],
) -> str:
    if payload.get("schema_version") != 1:
        raise ValueError(
            f"{path}: unsupported schema_version"
        )

    event_id = payload.get("event_id")

    if not isinstance(event_id, str) or not event_id:
        raise ValueError(
            f"{path}: event_id must be a non-empty string"
        )

    expected_name = f"{event_id}.json"

    if path.name != expected_name:
        raise ValueError(
            f"{path}: filename does not match event_id"
        )

    event = payload.get("event")

    if event not in VALID_EVENTS:
        raise ValueError(
            f"{path}: unsupported event={event!r}"
        )

    if payload.get("alert_required") is not True:
        raise ValueError(
            f"{path}: outbox event must require notification"
        )

    previous_status = payload.get(
        "previous_status"
    )

    if (
        previous_status is not None
        and previous_status not in VALID_STATUSES
    ):
        raise ValueError(
            f"{path}: invalid previous_status"
        )

    current_status = payload.get(
        "current_status"
    )

    if current_status not in VALID_STATUSES:
        raise ValueError(
            f"{path}: invalid current_status"
        )

    checker_exit_code = payload.get(
        "checker_exit_code"
    )

    if (
        checker_exit_code is not None
        and (
            not isinstance(checker_exit_code, int)
            or isinstance(checker_exit_code, bool)
        )
    ):
        raise ValueError(
            f"{path}: checker_exit_code must be int or null"
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

    checker_output = payload.get(
        "checker_output"
    )

    if not isinstance(checker_output, dict):
        raise ValueError(
            f"{path}: checker_output must be an object"
        )

    for key in (
        "stdout",
        "stderr",
    ):
        value = checker_output.get(key)

        if not isinstance(value, str):
            raise ValueError(
                f"{path}: checker_output.{key} must be a string"
            )

    return event_id


def utc_now_iso() -> str:
    return datetime.now(
        timezone.utc
    ).isoformat(
        timespec="seconds"
    )


def build_delivery_receipt(
    payload: dict[str, Any],
    transport: str,
) -> dict[str, Any]:
    event_id = payload["event_id"]

    return {
        "receipt_schema_version": 1,
        "event_id": event_id,
        "transport": transport,
        "delivered_at": utc_now_iso(),
        "event": payload,
    }


def validate_delivery_receipt(
    path: Path,
    receipt: dict[str, Any],
    payload: dict[str, Any],
    expected_transport: str,
) -> None:
    if receipt.get(
        "receipt_schema_version"
    ) != 1:
        raise ValueError(
            f"{path}: unsupported receipt_schema_version"
        )

    event_id = payload["event_id"]

    if receipt.get("event_id") != event_id:
        raise ValueError(
            f"{path}: receipt event_id mismatch"
        )

    if receipt.get("transport") != expected_transport:
        raise ValueError(
            f"{path}: unexpected receipt transport"
        )

    delivered_at = receipt.get(
        "delivered_at"
    )

    if (
        not isinstance(delivered_at, str)
        or not delivered_at
    ):
        raise ValueError(
            f"{path}: delivered_at must be a non-empty string"
        )

    if receipt.get("event") != payload:
        raise ValueError(
            f"{path}: receipt event payload mismatch"
        )


def deliver_to_local_sink(
    pending_path: Path,
    outbox_dir: Path,
    receipt_dir: Path,
    payload: dict[str, Any],
) -> str:
    receipt_dir.mkdir(
        parents=True,
        exist_ok=True,
    )

    receipt_path = (
        receipt_dir
        / pending_path.name
    )

    if receipt_path.exists():
        receipt = load_json(
            receipt_path
        )

        validate_delivery_receipt(
            receipt_path,
            receipt,
            payload,
            expected_transport="local-file",
        )

        pending_path.unlink()
        fsync_directory(
            outbox_dir
        )

        return "ACK_EXISTING"

    receipt = build_delivery_receipt(
        payload,
        transport="local-file",
    )

    temporary_path = (
        receipt_dir
        / (
            f".{pending_path.name}."
            f"{os.getpid()}.tmp"
        )
    )

    try:
        with temporary_path.open(
            "x",
            encoding="utf-8",
        ) as file:
            json.dump(
                receipt,
                file,
                ensure_ascii=False,
                separators=(",", ":"),
            )

            file.write("\n")
            file.flush()
            os.fsync(file.fileno())

        os.replace(
            temporary_path,
            receipt_path,
        )

        fsync_directory(
            receipt_dir
        )

        pending_path.unlink()

        fsync_directory(
            outbox_dir
        )
    except Exception:
        try:
            temporary_path.unlink(
                missing_ok=True
            )
        except OSError:
            pass

        raise

    return "DELIVERED"


def resolve_webhook_url(
    cli_url: str | None,
) -> str | None:
    if cli_url is not None:
        value = cli_url.strip()

        if value:
            return value

    environment_value = os.environ.get(
        "AEROTRACE_WEBHOOK_URL"
    )

    if environment_value is None:
        return None

    value = environment_value.strip()

    return value or None


def validate_webhook_url(
    url: str,
) -> None:
    try:
        parsed = urlsplit(
            url
        )
    except ValueError as exc:
        raise ValueError(
            "webhook URL is invalid"
        ) from exc

    if parsed.scheme not in {
        "http",
        "https",
    }:
        raise ValueError(
            "webhook URL scheme must be http or https"
        )

    if parsed.hostname is None:
        raise ValueError(
            "webhook URL must include a host"
        )

    if (
        parsed.username is not None
        or parsed.password is not None
    ):
        raise ValueError(
            "webhook URL must not contain userinfo credentials"
        )

    if parsed.fragment:
        raise ValueError(
            "webhook URL must not contain a fragment"
        )


def count_pending(
    outbox_dir: Path,
) -> int:
    if not outbox_dir.exists():
        return 0

    return sum(
        1
        for path in outbox_dir.glob("*.json")
        if path.is_file()
    )


def main() -> int:
    args = parse_args()

    if args.max_events <= 0:
        print(
            "adapter_error="
            "--max-events must be > 0",
            file=sys.stderr,
        )
        return 4

    if args.webhook_timeout_sec <= 0:
        print(
            "adapter_error="
            "--webhook-timeout-sec must be > 0",
            file=sys.stderr,
        )
        return 4

    webhook_url = None

    if args.transport == "local-file":
        if args.webhook_url is not None:
            print(
                "adapter_error="
                "--webhook-url requires "
                "--transport webhook",
                file=sys.stderr,
            )
            return 4
    else:
        webhook_url = resolve_webhook_url(
            args.webhook_url
        )

        if webhook_url is None:
            print(
                "adapter_error="
                "webhook URL is required via "
                "--webhook-url or "
                "AEROTRACE_WEBHOOK_URL",
                file=sys.stderr,
            )
            return 4

        try:
            validate_webhook_url(
                webhook_url
            )
        except ValueError as exc:
            print(
                f"adapter_error={exc}",
                file=sys.stderr,
            )
            return 4

        print(
            "adapter_error="
            "webhook transport is configured "
            "but HTTP delivery is not implemented yet",
            file=sys.stderr,
        )
        return 4

    if (
        args.outbox_dir.exists()
        and not args.outbox_dir.is_dir()
    ):
        print(
            "adapter_error="
            f"outbox path is not a directory: "
            f"{args.outbox_dir}",
            file=sys.stderr,
        )
        return 4

    if not args.outbox_dir.exists():
        if not args.quiet_idle:
            print("adapter_status=IDLE")
            print("processed_events=0")
            print("remaining_events=0")

        return 0

    pending_files = sorted(
        path
        for path in args.outbox_dir.glob("*.json")
        if path.is_file()
    )

    pending_files = pending_files[
        :args.max_events
    ]

    if not pending_files:
        if not args.quiet_idle:
            print("adapter_status=IDLE")
            print("processed_events=0")
            print("remaining_events=0")

        return 0

    processed = 0

    for pending_path in pending_files:
        try:
            payload = load_json(
                pending_path
            )

            event_id = validate_event(
                pending_path,
                payload,
            )
        except (
            OSError,
            ValueError,
            json.JSONDecodeError,
        ) as exc:
            print(
                "adapter_error="
                f"invalid pending event: {exc}",
                file=sys.stderr,
            )
            print(
                f"failed_event_file={pending_path}"
            )
            print(
                "remaining_events="
                f"{count_pending(args.outbox_dir)}"
            )

            return 3

        try:
            delivery_result = (
                deliver_to_local_sink(
                    pending_path=pending_path,
                    outbox_dir=args.outbox_dir,
                    receipt_dir=args.receipt_dir,
                    payload=payload,
                )
            )
        except ValueError as exc:
            print(
                "adapter_error="
                f"delivery contract failure: {exc}",
                file=sys.stderr,
            )
            print(
                f"failed_event_id={event_id}"
            )
            print(
                "remaining_events="
                f"{count_pending(args.outbox_dir)}"
            )

            return 3
        except OSError as exc:
            print(
                "adapter_error="
                f"delivery failed: {exc}",
                file=sys.stderr,
            )
            print(
                f"failed_event_id={event_id}"
            )
            print(
                "remaining_events="
                f"{count_pending(args.outbox_dir)}"
            )

            return 2

        processed += 1

        print(
            f"delivery_result={delivery_result}"
        )
        print(
            f"event_id={event_id}"
        )
        print(
            f"event={payload['event']}"
        )

    remaining = count_pending(
        args.outbox_dir
    )

    print("adapter_status=OK")
    print(
        f"processed_events={processed}"
    )
    print(
        f"remaining_events={remaining}"
    )

    return 0


if __name__ == "__main__":
    sys.exit(main())
