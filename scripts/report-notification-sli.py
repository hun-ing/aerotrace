#!/usr/bin/env python3

"""Report sender-to-receiver notification SLI from durable local files."""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable


UTC = timezone.utc


class ReportError(ValueError):
    """Raised when durable notification data cannot be trusted."""


@dataclass(frozen=True)
class EligibleEvent:
    event_id: str
    evaluated_at: datetime
    receipt_delivered_at: datetime | None


@dataclass(frozen=True)
class SliReport:
    status: str
    window_start: datetime
    window_end: datetime
    eligible_events: int
    accepted_events: int
    pending_events: int
    pending_with_receipt: int
    good_acceptance_events: int
    acceptance_miss_events: int
    clock_anomaly_events: int
    compliance_percent: float | None
    maximum_acceptance_latency_sec: float | None


def parse_iso_timestamp(value: Any, field_name: str) -> datetime:
    if not isinstance(value, str) or not value:
        raise ReportError(f"{field_name} must be a non-empty timestamp")

    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value

    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as error:
        raise ReportError(f"{field_name} must be an ISO-8601 timestamp") from error

    if parsed.tzinfo is None:
        raise ReportError(f"{field_name} must include a timezone")

    return parsed.astimezone(UTC)


def load_json_object(path: Path, data_kind: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ReportError(f"cannot read valid {data_kind} JSON") from error

    if not isinstance(value, dict):
        raise ReportError(f"{data_kind} JSON must be an object")

    return value


def event_identity(
    payload: dict[str, Any],
    data_kind: str,
) -> tuple[str, datetime]:
    event_id = payload.get("event_id")

    if not isinstance(event_id, str) or not event_id:
        raise ReportError(f"{data_kind} event_id must be a non-empty string")

    evaluated_at = parse_iso_timestamp(
        payload.get("evaluated_at"),
        f"{data_kind} evaluated_at",
    )
    return event_id, evaluated_at


def json_files(directory: Path, data_kind: str) -> Iterable[Path]:
    if not directory.exists():
        raise ReportError(f"{data_kind} directory does not exist")

    if not directory.is_dir():
        raise ReportError(f"{data_kind} path must be a directory")

    return sorted(
        path
        for path in directory.iterdir()
        if path.is_file() and not path.name.startswith(".") and path.suffix == ".json"
    )


def is_eligible(
    event_id: str,
    evaluated_at: datetime,
    window_start: datetime,
    window_end: datetime,
    excluded_prefixes: tuple[str, ...],
) -> bool:
    return (
        window_start <= evaluated_at <= window_end
        and not event_id.startswith(excluded_prefixes)
    )


def collect_receipts(
    receipt_dir: Path,
    window_start: datetime,
    window_end: datetime,
    excluded_prefixes: tuple[str, ...],
) -> dict[str, EligibleEvent]:
    receipts: dict[str, EligibleEvent] = {}

    for path in json_files(receipt_dir, "receipt"):
        receipt = load_json_object(path, "receipt")

        if receipt.get("transport") != "webhook":
            continue

        if receipt.get("receipt_schema_version") != 1:
            raise ReportError("unsupported Webhook receipt schema")

        payload = receipt.get("event")

        if not isinstance(payload, dict):
            raise ReportError("Webhook receipt event must be an object")

        event_id, evaluated_at = event_identity(payload, "receipt")

        if receipt.get("event_id") != event_id:
            raise ReportError("Webhook receipt event_id mismatch")

        if path.stem != event_id:
            raise ReportError("receipt filename and event_id mismatch")

        if not is_eligible(
            event_id,
            evaluated_at,
            window_start,
            window_end,
            excluded_prefixes,
        ):
            continue

        if event_id in receipts:
            raise ReportError("duplicate Webhook receipt event_id")

        receipts[event_id] = EligibleEvent(
            event_id=event_id,
            evaluated_at=evaluated_at,
            receipt_delivered_at=parse_iso_timestamp(
                receipt.get("delivered_at"),
                "receipt delivered_at",
            ),
        )

    return receipts


def collect_pending(
    outbox_dir: Path,
    window_start: datetime,
    window_end: datetime,
    excluded_prefixes: tuple[str, ...],
) -> dict[str, tuple[EligibleEvent, dict[str, Any]]]:
    pending: dict[str, tuple[EligibleEvent, dict[str, Any]]] = {}

    for path in json_files(outbox_dir, "outbox"):
        payload = load_json_object(path, "outbox event")
        event_id, evaluated_at = event_identity(payload, "outbox event")

        if path.stem != event_id:
            raise ReportError("outbox filename and event_id mismatch")

        if not is_eligible(
            event_id,
            evaluated_at,
            window_start,
            window_end,
            excluded_prefixes,
        ):
            continue

        if event_id in pending:
            raise ReportError("duplicate pending event_id")

        pending[event_id] = (
            EligibleEvent(
                event_id=event_id,
                evaluated_at=evaluated_at,
                receipt_delivered_at=None,
            ),
            payload,
        )

    return pending


def build_report(
    *,
    outbox_dir: Path,
    receipt_dir: Path,
    activation_at: datetime,
    now: datetime,
    window_days: float,
    acceptance_target_sec: float,
    objective_percent: float,
    excluded_prefixes: tuple[str, ...] = ("synthetic-", "smoke-"),
) -> SliReport:
    if activation_at.tzinfo is None or now.tzinfo is None:
        raise ReportError("activation_at and now must include timezones")

    if not math.isfinite(window_days) or window_days <= 0:
        raise ReportError("window_days must be greater than zero")

    if not math.isfinite(acceptance_target_sec) or acceptance_target_sec <= 0:
        raise ReportError("acceptance_target_sec must be greater than zero")

    if not math.isfinite(objective_percent) or not 0 < objective_percent <= 100:
        raise ReportError("objective_percent must be within (0, 100]")

    if not excluded_prefixes or any(not prefix for prefix in excluded_prefixes):
        raise ReportError("excluded event ID prefixes must be non-empty")

    activation_utc = activation_at.astimezone(UTC)
    window_end = now.astimezone(UTC)

    if activation_utc > window_end:
        raise ReportError("activation_at must not be after now")

    window_start = max(
        activation_utc,
        window_end - timedelta(days=window_days),
    )
    receipts = collect_receipts(
        receipt_dir,
        window_start,
        window_end,
        excluded_prefixes,
    )
    pending = collect_pending(
        outbox_dir,
        window_start,
        window_end,
        excluded_prefixes,
    )
    pending_with_receipt = 0

    for event_id, (_, payload) in pending.items():
        receipt = receipts.get(event_id)

        if receipt is None:
            continue

        receipt_payload = load_json_object(
            receipt_dir / f"{event_id}.json",
            "receipt",
        ).get("event")

        if receipt_payload != payload:
            raise ReportError("pending event and receipt payload mismatch")

        pending_with_receipt += 1

    unique_event_ids = set(receipts) | set(pending)
    pending_without_receipt = set(pending) - set(receipts)
    good = 0
    clock_anomalies = 0
    latencies: list[float] = []

    for receipt in receipts.values():
        if receipt.receipt_delivered_at is None:
            raise AssertionError("receipt must have delivered_at")

        if receipt.receipt_delivered_at > window_end:
            clock_anomalies += 1
            continue

        latency = (
            receipt.receipt_delivered_at - receipt.evaluated_at
        ).total_seconds()

        if latency < 0:
            clock_anomalies += 1
            continue

        latencies.append(latency)

        if latency <= acceptance_target_sec:
            good += 1

    eligible = len(unique_event_ids)
    misses = eligible - good
    compliance = None if eligible == 0 else (good / eligible) * 100

    if eligible == 0:
        status = "NO_DATA"
    elif clock_anomalies > 0:
        status = "UNKNOWN"
    elif compliance is not None and compliance + 1e-12 < objective_percent:
        status = "SLO_MISS"
    else:
        status = "OK"

    return SliReport(
        status=status,
        window_start=window_start,
        window_end=window_end,
        eligible_events=eligible,
        accepted_events=len(receipts),
        pending_events=len(pending_without_receipt),
        pending_with_receipt=pending_with_receipt,
        good_acceptance_events=good,
        acceptance_miss_events=misses,
        clock_anomaly_events=clock_anomalies,
        compliance_percent=compliance,
        maximum_acceptance_latency_sec=max(latencies, default=None),
    )


def iso_utc(value: datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def format_optional_number(value: float | None) -> str:
    return "N/A" if value is None else f"{value:.3f}"


def print_report(report: SliReport) -> None:
    print(f"status={report.status}")
    print(f"window_start={iso_utc(report.window_start)}")
    print(f"window_end={iso_utc(report.window_end)}")
    print(f"eligible_events={report.eligible_events}")
    print(f"accepted_events={report.accepted_events}")
    print(f"pending_events={report.pending_events}")
    print(f"pending_with_receipt={report.pending_with_receipt}")
    print(f"good_acceptance_events={report.good_acceptance_events}")
    print(f"acceptance_miss_events={report.acceptance_miss_events}")
    print(f"clock_anomaly_events={report.clock_anomaly_events}")
    print(
        "acceptance_compliance_percent="
        f"{format_optional_number(report.compliance_percent)}"
    )
    print(
        "maximum_acceptance_latency_sec="
        f"{format_optional_number(report.maximum_acceptance_latency_sec)}"
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Report rolling sender-to-receiver durable acceptance SLI from "
            "Webhook receipts and pending outbox events."
        ),
    )
    parser.add_argument("--outbox-dir", type=Path, required=True)
    parser.add_argument("--receipt-dir", type=Path, required=True)
    parser.add_argument("--activation-at", required=True)
    parser.add_argument("--window-days", type=float, default=30.0)
    parser.add_argument("--acceptance-target-sec", type=float, default=60.0)
    parser.add_argument("--objective-percent", type=float, default=99.0)
    parser.add_argument(
        "--exclude-event-id-prefix",
        action="append",
        dest="excluded_prefixes",
        default=["synthetic-", "smoke-"],
        help=(
            "Additional event ID prefix to exclude; synthetic- and smoke- "
            "are always excluded."
        ),
    )
    parser.add_argument(
        "--now",
        help="Override report end time for reproducible validation.",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    try:
        report = build_report(
            outbox_dir=args.outbox_dir,
            receipt_dir=args.receipt_dir,
            activation_at=parse_iso_timestamp(
                args.activation_at,
                "activation_at",
            ),
            now=(
                datetime.now(UTC)
                if args.now is None
                else parse_iso_timestamp(args.now, "now")
            ),
            window_days=args.window_days,
            acceptance_target_sec=args.acceptance_target_sec,
            objective_percent=args.objective_percent,
            excluded_prefixes=tuple(args.excluded_prefixes),
        )
    except ReportError as error:
        print("status=UNKNOWN")
        print(f"report_error={error}")
        return 3

    print_report(report)

    if report.status == "SLO_MISS":
        return 2

    if report.status == "UNKNOWN":
        return 3

    return 0


if __name__ == "__main__":
    sys.exit(main())
