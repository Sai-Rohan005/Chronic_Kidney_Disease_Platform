"""Bearer validation for all ML routes, matching the Java Redis jti allowlist."""
import base64
from datetime import datetime, timezone
from hmac import compare_digest
import os

import jwt
from redis import Redis
from redis.exceptions import RedisError
from starlette.responses import JSONResponse
from starlette.concurrency import run_in_threadpool


class JwtValidator:
    def __init__(self, encoded_secret, redis):
        try:
            self.key = base64.b64decode(encoded_secret or "", validate=True)
            if len(self.key) < 32 or base64.b64encode(self.key).decode() != encoded_secret:
                raise ValueError()
        except (ValueError, TypeError) as error:
            raise RuntimeError("JWT_SECRET is required and must encode at least 32 random bytes") from error
        self.redis = redis

    @classmethod
    def from_environment(cls):
        redis = Redis(host=os.environ.get("REDIS_HOST", "localhost"),
                      port=int(os.environ.get("REDIS_PORT", "6379")),
                      password=os.environ.get("REDIS_PASSWORD") or None,
                      decode_responses=True, socket_connect_timeout=2, socket_timeout=2)
        return cls(os.environ.get("JWT_SECRET"), redis)

    def validate(self, header):
        scheme, _, token = (header or "").partition(" ")
        if scheme.lower() != "bearer" or not token.strip():
            return None
        token = token.strip()
        try:
            claims = jwt.decode(token, self.key, algorithms=["HS256", "HS384", "HS512"],
                                options={"require": ["exp", "jti", "sub"]})
            if not claims["sub"].strip() or not claims["jti"].strip() or claims.get("role") not in {"Patient", "Doctor"}:
                return None
            stored = self.redis.get("auth:token:" + claims["jti"])
            if not isinstance(stored, str) or not compare_digest(stored, token):
                return None
            return claims
        except (jwt.PyJWTError, RedisError, ConnectionError, ValueError, TypeError, AttributeError):
            return None


def install_security(app, validator):
    @app.middleware("http")
    async def require_doctor(request, call_next):
        claims = await run_in_threadpool(validator.validate, request.headers.get("Authorization"))
        status = 401 if claims is None else 403 if claims["role"] != "Doctor" else None
        if status is not None:
            return JSONResponse(status_code=status, content={
                "timestamp": datetime.now(timezone.utc).isoformat(), "status": status,
                "error": "Unauthorized" if status == 401 else "Forbidden",
                "message": "Invalid, expired, or revoked token" if status == 401 else "Doctor role required",
                "path": request.url.path,
            }, headers={"WWW-Authenticate": "Bearer"} if status == 401 else None)
        request.state.authenticated_user = claims["sub"]
        return await call_next(request)
