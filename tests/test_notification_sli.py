import importlib.util
import json
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = ROOT / "scripts" / "report-notification-sli.py"
SPEC = importlib.util.spec_from_file_location("notification_sli", SCRIPT_PATH)

if SPEC is None or SPEC.loader is None:
    raise RuntimeError("cannot load notification SLI reporter")

SLI = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = SLI
SPEC.loader.exec_module(SLI)


UTC = timezone.utc
ACTIVATION = datetime(2026, 8, 21, 7, 18, tzinfo=UTC)
NOW = datetime(2026, 8, 22, 7, 18, tzinfo=UTC)


class NotificationSliTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.outbox_dir = self.root / "outbox"
        self.receipt_dir = self.root / "receipts"
        self.outbox_dir.mkdir()
        self.receipt_dir.mkdir()

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def event(
        self,
        event_id: str,
        evaluated_at: str,
    ) -> dict[str, object]:
        return {
            "schema_version": 1,
            "event_id": event_id,
            "event": "ALERT",
            "alert_required": True,
            "previous_status": "OK",
            "current_status": "CRITICAL",
            "checker_exit_code": 2,
            "state_file": "/tmp/collector-state.json",
            "evaluated_at": evaluated_at,
            "checker_output": {"stdout": "status=CRITICAL", "stderr": ""},
        }

    def write_pending(self, payload: dict[str, object]) -> None:
        path = self.outbox_dir / f"{payload['event_id']}.json"
        path.write_text(json.dumps(payload), encoding="utf-8")

    def write_receipt(
        self,
        payload: dict[str, object],
        delivered_at: str,
        transport: str = "webhook",
    ) -> None:
        receipt = {
            "receipt_schema_version": 1,
            "event_id": payload["event_id"],
            "transport": transport,
            "delivered_at": delivered_at,
            "event": payload,
        }
        path = self.receipt_dir / f"{payload['event_id']}.json"
        path.write_text(json.dumps(receipt), encoding="utf-8")

    def report(self):
        return SLI.build_report(
            outbox_dir=self.outbox_dir,
            receipt_dir=self.receipt_dir,
            activation_at=ACTIVATION,
            now=NOW,
            window_days=30,
            acceptance_target_sec=60,
            objective_percent=99,
        )

    def test_no_eligible_event_is_no_data(self) -> None:
        report = self.report()

        self.assertEqual(report.status, "NO_DATA")
        self.assertEqual(report.eligible_events, 0)
        self.assertIsNone(report.compliance_percent)

    def test_good_webhook_receipt_meets_acceptance_sli(self) -> None:
        payload = self.event("event-good", "2026-08-22T07:17:30Z")
        self.write_receipt(payload, "2026-08-22T07:17:40Z")
        local_payload = self.event("event-local", "2026-08-22T07:17:30Z")
        self.write_receipt(
            local_payload,
            "2026-08-22T07:17:40Z",
            transport="local-file",
        )

        report = self.report()

        self.assertEqual(report.status, "OK")
        self.assertEqual(report.eligible_events, 1)
        self.assertEqual(report.accepted_events, 1)
        self.assertEqual(report.good_acceptance_events, 1)
        self.assertEqual(report.maximum_acceptance_latency_sec, 10)

    def test_late_receipt_and_pending_event_are_slo_misses(self) -> None:
        late = self.event("event-late", "2026-08-22T07:15:00Z")
        self.write_receipt(late, "2026-08-22T07:17:00Z")
        pending = self.event("event-pending", "2026-08-22T07:16:00Z")
        self.write_pending(pending)

        report = self.report()

        self.assertEqual(report.status, "SLO_MISS")
        self.assertEqual(report.eligible_events, 2)
        self.assertEqual(report.accepted_events, 1)
        self.assertEqual(report.pending_events, 1)
        self.assertEqual(report.good_acceptance_events, 0)
        self.assertEqual(report.acceptance_miss_events, 2)

    def test_exclusions_and_ack_existing_window_do_not_double_count(self) -> None:
        before_activation = self.event(
            "event-before",
            "2026-08-21T07:17:59Z",
        )
        self.write_receipt(before_activation, "2026-08-21T07:18:01Z")
        synthetic = self.event(
            "synthetic-123",
            "2026-08-22T07:17:30Z",
        )
        self.write_receipt(synthetic, "2026-08-22T07:17:40Z")
        crash_window = self.event(
            "event-crash-window",
            "2026-08-22T07:17:30Z",
        )
        self.write_receipt(crash_window, "2026-08-22T07:17:40Z")
        self.write_pending(crash_window)

        report = self.report()

        self.assertEqual(report.status, "OK")
        self.assertEqual(report.eligible_events, 1)
        self.assertEqual(report.accepted_events, 1)
        self.assertEqual(report.pending_events, 0)
        self.assertEqual(report.pending_with_receipt, 1)

    def test_receipt_filename_must_match_event_id(self) -> None:
        payload = self.event("event-filename", "2026-08-22T07:17:30Z")
        receipt = {
            "receipt_schema_version": 1,
            "event_id": payload["event_id"],
            "transport": "webhook",
            "delivered_at": "2026-08-22T07:17:40Z",
            "event": payload,
        }
        (self.receipt_dir / "wrong-name.json").write_text(
            json.dumps(receipt),
            encoding="utf-8",
        )

        with self.assertRaisesRegex(
            SLI.ReportError,
            "receipt filename and event_id mismatch",
        ):
            self.report()

    def test_future_receipt_timestamp_is_unknown(self) -> None:
        payload = self.event("event-future", "2026-08-22T07:17:30Z")
        self.write_receipt(payload, "2026-08-22T07:18:01Z")

        report = self.report()

        self.assertEqual(report.status, "UNKNOWN")
        self.assertEqual(report.clock_anomaly_events, 1)
        self.assertEqual(report.good_acceptance_events, 0)

    def test_missing_directory_and_future_activation_are_rejected(self) -> None:
        self.receipt_dir.rmdir()

        with self.assertRaisesRegex(
            SLI.ReportError,
            "receipt directory does not exist",
        ):
            self.report()

        self.receipt_dir.mkdir()

        with self.assertRaisesRegex(
            SLI.ReportError,
            "activation_at must not be after now",
        ):
            SLI.build_report(
                outbox_dir=self.outbox_dir,
                receipt_dir=self.receipt_dir,
                activation_at=datetime(2026, 8, 22, 7, 18, 1, tzinfo=UTC),
                now=NOW,
                window_days=30,
                acceptance_target_sec=60,
                objective_percent=99,
            )


if __name__ == "__main__":
    unittest.main()
