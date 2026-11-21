"""Security tests use a toy route so loading Torch/OCR models is unnecessary."""
import base64
from datetime import datetime, timedelta, timezone
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import Mock
import uuid

from fastapi import FastAPI
from fastapi.testclient import TestClient
import jwt

spec = importlib.util.spec_from_file_location("ml_security", Path(__file__).parents[1] / "security.py")
security = importlib.util.module_from_spec(spec)
spec.loader.exec_module(security)

class MLSecurityTest(unittest.TestCase):
    def setUp(self):
        self.key = os.urandom(32)
        self.active = {}
        self.redis = Mock()
        self.redis.get.side_effect = self.active.get
        validator = security.JwtValidator(base64.b64encode(self.key).decode(), self.redis)
        app = FastAPI()
        security.install_security(app, validator)
        @app.post("/routes/tab_predict")
        def predict(): return {"test_fixture": True}
        self.client = TestClient(app)

    def bearer(self, role="Doctor", active=True, expired=False):
        jti = str(uuid.uuid4())
        payload = {"sub": "doctor@example.invalid", "role": role, "jti": jti,
                   "exp": datetime.now(timezone.utc) + timedelta(seconds=-60 if expired else 60)}
        token = jwt.encode(payload, self.key, algorithm="HS256")
        if active: self.active["auth:token:" + jti] = token
        return {"Authorization": "Bearer " + token}

    def test_no_token_is_401(self):
        result = self.client.post("/routes/tab_predict", json={})
        self.assertEqual(401, result.status_code)
        self.assertEqual({"timestamp", "status", "error", "message", "path"}, set(result.json()))
    def test_expired_token_is_401(self):
        self.assertEqual(401, self.client.post("/routes/tab_predict", headers=self.bearer(expired=True), json={}).status_code)
    def test_revoked_token_is_401(self):
        self.assertEqual(401, self.client.post("/routes/tab_predict", headers=self.bearer(active=False), json={}).status_code)
    def test_patient_is_403(self):
        self.assertEqual(403, self.client.post("/routes/tab_predict", headers=self.bearer(role="Patient"), json={}).status_code)
    def test_doctor_can_call(self):
        self.assertEqual(200, self.client.post("/routes/tab_predict", headers=self.bearer(), json={}).status_code)
    def test_secret_is_required_and_must_be_long_enough(self):
        for value in [None, "", "not base64!", base64.b64encode(os.urandom(31)).decode(), base64.b64encode(self.key).decode() + "!"]:
            with self.assertRaises(RuntimeError): security.JwtValidator(value, self.redis)

    def test_redis_outage_fails_closed(self):
        self.redis.get.side_effect = ConnectionError("unavailable")
        self.assertEqual(401, self.client.post("/routes/tab_predict", headers=self.bearer(), json={}).status_code)

if __name__ == "__main__": unittest.main()
