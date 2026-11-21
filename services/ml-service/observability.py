"""Bounded correlation metadata and JSON logs without request/model payloads."""
from contextvars import ContextVar
from datetime import datetime, timezone
import json
import logging
import re
import uuid

request_id = ContextVar("request_id", default=None)
_SAFE_ID = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
_EVENTS = {"request_completed", "model_loaded", "model_failure"}
_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"}


class SafeJsonFormatter(logging.Formatter):
    def format(self, record):
        # Never interpolate arbitrary log messages, exception text, URLs, or request headers.
        event = getattr(record, "event", "internal_log")
        payload = {"@timestamp": datetime.now(timezone.utc).isoformat(), "level": record.levelname,
                   "service": "CKD_ML_SERVICE", "message": event if event in _EVENTS else "internal_log"}
        correlation = request_id.get()
        if correlation:
            payload["requestId"] = correlation
        if getattr(record, "status", None) is not None:
            payload["status"] = int(record.status)
        method = getattr(record, "method", None)
        if method in _METHODS:
            payload["method"] = method
        return json.dumps(payload)


def configure_logging():
    handler = logging.StreamHandler()
    handler.setFormatter(SafeJsonFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(logging.INFO)
    logging.captureWarnings(True)
    # Uvicorn access messages contain the raw URL/query; use the safe completion event instead.
    logging.getLogger("uvicorn.access").disabled = True
    for name in ("uvicorn", "uvicorn.error"):
        logger = logging.getLogger(name)
        logger.handlers = []
        logger.propagate = True


class RequestCorrelationMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        supplied = next((value.decode("latin1") for name, value in scope.get("headers", []) if name.lower() == b"x-request-id"), None)
        correlation = supplied if supplied and _SAFE_ID.fullmatch(supplied) else str(uuid.uuid4())
        token = request_id.set(correlation)
        status = 500

        async def correlated_send(event):
            nonlocal status
            if event["type"] == "http.response.start":
                status = event["status"]
                event = dict(event)
                event["headers"] = [(key, value) for key, value in event.get("headers", []) if key.lower() != b"x-request-id"]
                event["headers"].append((b"x-request-id", correlation.encode("ascii")))
            await send(event)

        try:
            await self.app(scope, receive, correlated_send)
        finally:
            logging.getLogger("ckd.request").info("request_completed", extra={"event": "request_completed", "status": status,
                                                                            "method": scope.get("method", "OTHER")})
            request_id.reset(token)
