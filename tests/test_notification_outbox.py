#!/usr/bin/env python3

import contextlib
import hashlib
import hmac
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import ModuleType
from typing import Iterator


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
ADAPTER_PATH = (
    REPOSITORY_ROOT
    / "scripts"
    / "process-notification-outbox.py"
)
TEST_SIGNING_SECRET = "test-signing-secret-32-bytes-minimum"


def load_adapter_module() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "aerotrace_notification_outbox",
        ADAPTER_PATH,
    )

    if spec is None or spec.loader is None:
        raise RuntimeError(
            "failed to load notification outbox adapter"
        )

    module = importlib.util.module_from_spec(
        spec
    )
    spec.loader.exec_module(
        module
    )

    return module


ADAPTER = load_adapter_module()


class RecordingWebhookHandler(
    BaseHTTPRequestHandler
):
    def do_POST(self) -> None:
        content_length = int(
            self.headers.get(
                "Content-Length",
                "0",
            )
        )
        body = self.rfile.read(
            content_length
        )

        self.server.requests.append(
            {
                "path": self.path,
                "headers": dict(
                    self.headers.items()
                ),
                "body": body,
            }
        )

        self.send_response(
            self.server.response_status
        )
        self.send_header(
            "Content-Length",
            "0",
        )
        self.end_headers()

    def log_message(
        self,
        format: str,
        *args: object,
    ) -> None:
        return


@contextlib.contextmanager
def run_webhook_receiver(
    response_status: int,
) -> Iterator[ThreadingHTTPServer]:
    server = ThreadingHTTPServer(
        ("127.0.0.1", 0),
        RecordingWebhookHandler,
    )
    server.requests = []
    server.response_status = (
        response_status
    )

    thread = threading.Thread(
        target=server.serve_forever,
        daemon=True,
    )
    thread.start()

    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


class NotificationOutboxTest(
    unittest.TestCase
):
    def setUp(self) -> None:
        self.temporary_directory = (
            tempfile.TemporaryDirectory()
        )
        self.addCleanup(
            self.temporary_directory.cleanup
        )

        self.root = Path(
            self.temporary_directory.name
        )
        self.outbox_dir = (
            self.root / "outbox"
        )
        self.receipt_dir = (
            self.root / "receipts"
        )
        self.failure_state_file = (
            self.root / "failure.json"
        )

        self.outbox_dir.mkdir()

    def build_event(
        self,
        event_id: str = "test-event-1",
    ) -> dict[str, object]:
        return {
            "schema_version": 1,
            "event_id": event_id,
            "event": "ALERT",
            "alert_required": True,
            "previous_status": "OK",
            "current_status": "CRITICAL",
            "checker_exit_code": 2,
            "state_file": (
                "/tmp/collector-queue-alert.json"
            ),
            "evaluated_at": (
                "2026-08-21T00:00:00+00:00"
            ),
            "checker_output": {
                "stdout": "status=CRITICAL",
                "stderr": "",
            },
        }

    def write_event(
        self,
        payload: dict[str, object],
    ) -> Path:
        path = (
            self.outbox_dir
            / f"{payload['event_id']}.json"
        )
        path.write_text(
            json.dumps(
                payload,
                ensure_ascii=False,
                separators=(",", ":"),
            )
            + "\n",
            encoding="utf-8",
        )

        return path

    def run_adapter(
        self,
        *extra_args: str,
        webhook_url: str | None = None,
        use_failure_state: bool = True,
        transport: str = "webhook",
        webhook_signing_secret: str | None = (
            TEST_SIGNING_SECRET
        ),
    ) -> subprocess.CompletedProcess[str]:
        command = [
            sys.executable,
            str(ADAPTER_PATH),
            "--outbox-dir",
            str(self.outbox_dir),
            "--receipt-dir",
            str(self.receipt_dir),
            "--transport",
            transport,
            "--max-events",
            "1",
        ]

        if transport == "webhook":
            if webhook_url is None:
                raise ValueError(
                    "webhook_url is required"
                )

            command.extend(
                [
                    "--webhook-url",
                    webhook_url,
                    "--webhook-timeout-sec",
                    "2",
                ]
            )

            if use_failure_state:
                command.extend(
                    [
                        "--failure-state-file",
                        str(
                            self.failure_state_file
                        ),
                    ]
                )

        command.extend(
            extra_args
        )

        environment = os.environ.copy()
        environment.pop(
            "AEROTRACE_WEBHOOK_URL",
            None,
        )
        environment.pop(
            "AEROTRACE_WEBHOOK_SIGNING_SECRET",
            None,
        )

        if (
            transport == "webhook"
            and webhook_signing_secret is not None
        ):
            environment[
                "AEROTRACE_WEBHOOK_SIGNING_SECRET"
            ] = webhook_signing_secret

        environment[
            "PYTHONDONTWRITEBYTECODE"
        ] = "1"

        return subprocess.run(
            command,
            cwd=REPOSITORY_ROOT,
            env=environment,
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )

    def expire_backoff(self) -> None:
        state = json.loads(
            self.failure_state_file.read_text(
                encoding="utf-8"
            )
        )
        past = (
            datetime.now(timezone.utc)
            - timedelta(minutes=10)
        ).isoformat(timespec="seconds")

        state["first_failed_at"] = past
        state["last_failed_at"] = past

        self.failure_state_file.write_text(
            json.dumps(
                state,
                ensure_ascii=False,
                separators=(",", ":"),
            )
            + "\n",
            encoding="utf-8",
        )

    def test_bounded_backoff_calculation(
        self,
    ) -> None:
        calculate = ADAPTER.calculate_retryable_backoff_sec

        actual = [
            calculate(
                failure_count,
                5.0,
                300.0,
            )
            for failure_count in range(1, 10)
        ]

        self.assertEqual(
            actual,
            [
                5.0,
                10.0,
                20.0,
                40.0,
                80.0,
                160.0,
                300.0,
                300.0,
                300.0,
            ],
        )

        with self.assertRaises(ValueError):
            calculate(0, 5.0, 300.0)

    def test_invalid_backoff_configuration(
        self,
    ) -> None:
        cases = (
            (
                (
                    "--retryable-backoff-initial-sec",
                    "0",
                ),
                "positive finite number",
            ),
            (
                (
                    "--retryable-backoff-initial-sec",
                    "NaN",
                ),
                "positive finite number",
            ),
            (
                (
                    "--retryable-backoff-initial-sec",
                    "10",
                    "--retryable-backoff-max-sec",
                    "5",
                ),
                "greater than or equal",
            ),
        )

        for arguments, expected_error in cases:
            with self.subTest(
                arguments=arguments
            ):
                result = self.run_adapter(
                    *arguments,
                    transport="local-file",
                    use_failure_state=False,
                )

                self.assertEqual(
                    result.returncode,
                    4,
                    result,
                )
                self.assertIn(
                    expected_error,
                    result.stderr,
                )

    def test_http_status_classification(
        self,
    ) -> None:
        for status in (
            408,
            429,
            500,
            503,
            599,
        ):
            with self.subTest(
                status=status
            ):
                error = (
                    ADAPTER.webhook_error_for_status(
                        status
                    )
                )
                self.assertIsInstance(
                    error,
                    ADAPTER.WebhookRetryableError,
                )
                self.assertEqual(
                    error.failure_reason,
                    f"http_{status}",
                )

        for status in (
            300,
            302,
            400,
            409,
            422,
        ):
            with self.subTest(
                status=status
            ):
                error = (
                    ADAPTER.webhook_error_for_status(
                        status
                    )
                )
                self.assertIsInstance(
                    error,
                    ADAPTER.WebhookPermanentError,
                )
                self.assertEqual(
                    error.failure_reason,
                    f"http_{status}",
                )

    def test_webhook_signature_calculation(
        self,
    ) -> None:
        request_body = "{\"message\":\"안녕\"}".encode(
            "utf-8"
        )
        timestamp = 1787281200
        signing_secret = TEST_SIGNING_SECRET.encode(
            "utf-8"
        )

        headers = (
            ADAPTER.build_webhook_signature_headers(
                request_body=request_body,
                signing_secret=signing_secret,
                timestamp=timestamp,
            )
        )
        expected_digest = hmac.new(
            signing_secret,
            b"v1.1787281200." + request_body,
            hashlib.sha256,
        ).hexdigest()

        self.assertEqual(
            headers["X-AeroTrace-Timestamp"],
            str(timestamp),
        )
        self.assertEqual(
            headers["X-AeroTrace-Signature"],
            f"v1={expected_digest}",
        )

    def test_webhook_signing_secret_configuration(
        self,
    ) -> None:
        self.write_event(
            self.build_event()
        )

        cases = (
            (
                None,
                "AEROTRACE_WEBHOOK_SIGNING_SECRET is required",
            ),
            (
                "too-short",
                "at least 32 UTF-8 bytes",
            ),
            (
                TEST_SIGNING_SECRET + " ",
                "surrounding whitespace",
            ),
        )

        for signing_secret, expected_error in cases:
            with self.subTest(
                signing_secret=signing_secret
            ):
                result = self.run_adapter(
                    webhook_url="http://127.0.0.1:1/test",
                    webhook_signing_secret=signing_secret,
                )

                self.assertEqual(
                    result.returncode,
                    4,
                    result,
                )
                self.assertIn(
                    expected_error,
                    result.stderr,
                )

    def test_local_file_delivery_smoke(
        self,
    ) -> None:
        payload = self.build_event()
        pending_path = self.write_event(
            payload
        )

        result = self.run_adapter(
            transport="local-file",
            use_failure_state=False,
        )

        self.assertEqual(
            result.returncode,
            0,
            result,
        )
        self.assertIn(
            "delivery_result=DELIVERED",
            result.stdout,
        )
        self.assertFalse(
            pending_path.exists()
        )

        receipt_path = (
            self.receipt_dir
            / pending_path.name
        )
        receipt = json.loads(
            receipt_path.read_text(
                encoding="utf-8"
            )
        )

        self.assertEqual(
            receipt["transport"],
            "local-file",
        )
        self.assertEqual(
            receipt["event_id"],
            payload["event_id"],
        )

    def test_retryable_defer_and_recovery(
        self,
    ) -> None:
        payload = self.build_event()
        pending_path = self.write_event(
            payload
        )

        with run_webhook_receiver(503) as server:
            webhook_url = (
                "http://127.0.0.1:"
                f"{server.server_port}/notification"
            )

            first_failure = self.run_adapter(
                webhook_url=webhook_url
            )

            self.assertEqual(
                first_failure.returncode,
                2,
                first_failure,
            )
            self.assertEqual(
                len(server.requests),
                1,
            )

            request = server.requests[0]
            request_payload = json.loads(
                request["body"].decode("utf-8")
            )

            self.assertEqual(
                request_payload,
                payload,
            )
            self.assertEqual(
                request["headers"][
                    "X-Aerotrace-Event-Id"
                ],
                payload["event_id"],
            )
            self.assertEqual(
                request["headers"][
                    "Content-Type"
                ],
                "application/json",
            )
            self.assertEqual(
                request["headers"]["Accept"],
                "application/json",
            )
            self.assertEqual(
                request["headers"][
                    "User-Agent"
                ],
                "AeroTrace-Notification/1",
            )
            timestamp = request["headers"][
                "X-Aerotrace-Timestamp"
            ]
            signature = request["headers"][
                "X-Aerotrace-Signature"
            ]
            expected_signature = hmac.new(
                TEST_SIGNING_SECRET.encode("utf-8"),
                (
                    b"v1."
                    + timestamp.encode("ascii")
                    + b"."
                    + request["body"]
                ),
                hashlib.sha256,
            ).hexdigest()

            self.assertEqual(
                signature,
                f"v1={expected_signature}",
            )

            first_state = (
                self.failure_state_file.read_bytes()
            )
            first_deferred = self.run_adapter(
                webhook_url=webhook_url
            )

            self.assertEqual(
                first_deferred.returncode,
                0,
                first_deferred,
            )
            self.assertIn(
                "delivery_result="
                "DEFERRED_RETRYABLE_FAILURE",
                first_deferred.stdout,
            )
            self.assertEqual(
                len(server.requests),
                1,
            )
            self.assertEqual(
                self.failure_state_file.read_bytes(),
                first_state,
            )

            self.expire_backoff()

            second_failure = self.run_adapter(
                webhook_url=webhook_url
            )

            self.assertEqual(
                second_failure.returncode,
                2,
                second_failure,
            )
            self.assertEqual(
                len(server.requests),
                2,
            )

            second_state = json.loads(
                self.failure_state_file.read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(
                second_state["failure_count"],
                2,
            )

            second_state_bytes = (
                self.failure_state_file.read_bytes()
            )
            second_deferred = self.run_adapter(
                webhook_url=webhook_url
            )

            self.assertEqual(
                second_deferred.returncode,
                0,
                second_deferred,
            )
            self.assertEqual(
                len(server.requests),
                2,
            )
            self.assertEqual(
                self.failure_state_file.read_bytes(),
                second_state_bytes,
            )

            self.expire_backoff()
            server.response_status = 204

            recovery = self.run_adapter(
                webhook_url=webhook_url
            )

            self.assertEqual(
                recovery.returncode,
                0,
                recovery,
            )
            self.assertIn(
                "delivery_result=DELIVERED",
                recovery.stdout,
            )
            self.assertEqual(
                len(server.requests),
                3,
            )
            self.assertFalse(
                pending_path.exists()
            )
            self.assertFalse(
                self.failure_state_file.exists()
            )
            self.assertTrue(
                (
                    self.receipt_dir
                    / pending_path.name
                ).is_file()
            )

    def test_ack_existing_precedes_backoff(
        self,
    ) -> None:
        payload = self.build_event()
        pending_path = self.write_event(
            payload
        )
        self.receipt_dir.mkdir()

        receipt = ADAPTER.build_delivery_receipt(
            payload,
            transport="webhook",
        )
        (
            self.receipt_dir
            / pending_path.name
        ).write_text(
            json.dumps(
                receipt,
                separators=(",", ":"),
            )
            + "\n",
            encoding="utf-8",
        )

        now = datetime.now(
            timezone.utc
        ).isoformat(timespec="seconds")
        failure_state = {
            "failure_state_schema_version": 1,
            "transport": "webhook",
            "failed_event_id": payload[
                "event_id"
            ],
            "failure_kind": "retryable",
            "failure_reason": "http_503",
            "first_failed_at": now,
            "last_failed_at": now,
            "failure_count": 4,
        }
        self.failure_state_file.write_text(
            json.dumps(
                failure_state,
                separators=(",", ":"),
            )
            + "\n",
            encoding="utf-8",
        )

        with run_webhook_receiver(503) as server:
            webhook_url = (
                "http://127.0.0.1:"
                f"{server.server_port}/notification"
            )
            result = self.run_adapter(
                webhook_url=webhook_url
            )

            self.assertEqual(
                result.returncode,
                0,
                result,
            )
            self.assertIn(
                "delivery_result=ACK_EXISTING",
                result.stdout,
            )
            self.assertEqual(
                len(server.requests),
                0,
            )

        self.assertFalse(
            pending_path.exists()
        )
        self.assertFalse(
            self.failure_state_file.exists()
        )

    def test_permanent_latch_and_explicit_retry(
        self,
    ) -> None:
        payload = self.build_event()
        pending_path = self.write_event(
            payload
        )

        with run_webhook_receiver(400) as server:
            webhook_url = (
                "http://127.0.0.1:"
                f"{server.server_port}/notification"
            )

            first_failure = self.run_adapter(
                webhook_url=webhook_url
            )
            self.assertEqual(
                first_failure.returncode,
                5,
                first_failure,
            )
            self.assertEqual(
                len(server.requests),
                1,
            )

            latched_state = (
                self.failure_state_file.read_bytes()
            )
            blocked = self.run_adapter(
                webhook_url=webhook_url
            )

            self.assertEqual(
                blocked.returncode,
                5,
                blocked,
            )
            self.assertIn(
                "delivery_result="
                "BLOCKED_PERMANENT_FAILURE",
                blocked.stdout,
            )
            self.assertEqual(
                len(server.requests),
                1,
            )
            self.assertEqual(
                self.failure_state_file.read_bytes(),
                latched_state,
            )

            explicit_failure = self.run_adapter(
                "--retry-permanent-failure",
                webhook_url=webhook_url,
            )
            self.assertEqual(
                explicit_failure.returncode,
                5,
                explicit_failure,
            )
            self.assertEqual(
                len(server.requests),
                2,
            )

            state = json.loads(
                self.failure_state_file.read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(
                state["failure_count"],
                2,
            )
            self.assertEqual(
                state["failure_kind"],
                "permanent",
            )

            server.response_status = 204
            recovery = self.run_adapter(
                "--retry-permanent-failure",
                webhook_url=webhook_url,
            )

            self.assertEqual(
                recovery.returncode,
                0,
                recovery,
            )
            self.assertEqual(
                len(server.requests),
                3,
            )
            self.assertFalse(
                pending_path.exists()
            )
            self.assertFalse(
                self.failure_state_file.exists()
            )
            self.assertTrue(
                (
                    self.receipt_dir
                    / pending_path.name
                ).is_file()
            )

    def test_retry_permanent_configuration(
        self,
    ) -> None:
        local_file_result = self.run_adapter(
            "--retry-permanent-failure",
            transport="local-file",
            use_failure_state=False,
        )

        self.assertEqual(
            local_file_result.returncode,
            4,
            local_file_result,
        )
        self.assertIn(
            "requires --transport webhook",
            local_file_result.stderr,
        )

        webhook_result = self.run_adapter(
            "--retry-permanent-failure",
            webhook_url="http://127.0.0.1:1/test",
            use_failure_state=False,
        )

        self.assertEqual(
            webhook_result.returncode,
            4,
            webhook_result,
        )
        self.assertIn(
            "requires --failure-state-file",
            webhook_result.stderr,
        )


if __name__ == "__main__":
    unittest.main()
