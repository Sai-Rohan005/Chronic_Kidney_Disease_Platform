import asyncio
import importlib.util
import io
import json
import logging
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("ml_observability", Path(__file__).parents[1] / "observability.py")
observability = importlib.util.module_from_spec(spec)
spec.loader.exec_module(observability)


class CorrelationTests(unittest.TestCase):
    def run_request(self, request_id=None, status=200):
        sent = []
        async def app(scope, receive, send):
            self.assertIsNotNone(observability.request_id.get())
            await send({"type": "http.response.start", "status": status, "headers": []})
            await send({"type": "http.response.body", "body": b""})
        async def send(event):
            sent.append(event)
        scope = {"type": "http", "method": "GET", "path": "/private@example.invalid",
                 "headers": [(b"authorization", b"Bearer secret-token")]}
        if request_id:
            scope["headers"].append((b"x-request-id", request_id.encode()))
        asyncio.run(observability.RequestCorrelationMiddleware(app)(scope, None, send))
        self.assertIsNone(observability.request_id.get())
        return dict(sent[0]["headers"])[b"x-request-id"].decode()

    def test_preserves_safe_id_and_generates_missing_or_invalid_id(self):
        import uuid
        self.assertEqual("test-request-123", self.run_request("test-request-123"))
        uuid.UUID(self.run_request())
        uuid.UUID(self.run_request("private@example.invalid"))

    def test_json_log_contains_correlation_status_but_no_patient_or_token(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        handler.setFormatter(observability.SafeJsonFormatter())
        logger = logging.getLogger("ckd.request")
        previous = logger.level
        logger.setLevel(logging.INFO)
        logger.addHandler(handler)
        try:
            self.run_request("test-request-123", 403)
            event = json.loads(stream.getvalue())
            self.assertEqual("test-request-123", event["requestId"])
            self.assertEqual(403, event["status"])
            self.assertNotIn("private", stream.getvalue())
            self.assertNotIn("secret-token", stream.getvalue())
        finally:
            logger.removeHandler(handler)
            logger.setLevel(previous)

    def test_exception_message_is_never_formatted(self):
        record = logging.LogRecord("ckd.model", logging.ERROR, "", 0, "patient@example.invalid token=secret", (), None)
        event = json.loads(observability.SafeJsonFormatter().format(record))
        self.assertEqual("internal_log", event["message"])
        self.assertNotIn("patient", str(event))
