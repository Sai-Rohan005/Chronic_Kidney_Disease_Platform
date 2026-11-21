"""Probe the protected ML app without creating a public authentication bypass."""
import sys
import time
import uuid
from urllib.request import Request, urlopen
import jwt
from .security import JwtValidator


def probe():
    validator = None
    key = None
    try:
        validator = JwtValidator.from_environment()
        jti = str(uuid.uuid4())
        token = jwt.encode({"sub": "ml-healthcheck", "role": "Doctor", "jti": jti,
                            "iat": int(time.time()), "exp": int(time.time()) + 15}, validator.key, algorithm="HS256")
        key = "auth:token:" + jti
        validator.redis.set(key, token, ex=15)
        request = Request("http://127.0.0.1:8001/", headers={"Authorization": "Bearer " + token})
        with urlopen(request, timeout=5) as response:
            return response.status == 200
    except Exception:
        return False
    finally:
        if validator is not None and key is not None:
            try:
                validator.redis.delete(key)
            except Exception:
                pass  # The short Redis TTL still bounds this probe credential's lifetime.


if __name__ == "__main__":
    sys.exit(0 if probe() else 1)
