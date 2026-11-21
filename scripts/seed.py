"""Create local demo fixtures without embedding passwords or bypassing public registration roles."""
import argparse
from datetime import date
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def read_env(path):
    values = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, raw = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key.strip()):
            raise ValueError("Invalid .env assignment")
        pieces = shlex.split(raw, comments=True, posix=True)
        values[key.strip()] = " ".join(pieces)
    values.update(os.environ)
    return values


def settings(values):
    for key in ("DEMO_PATIENT_PASSWORD", "DEMO_DOCTOR_PASSWORD"):
        password = values.get(key, "")
        if len(password) < 12 or len(password.encode()) > 72:
            raise ValueError(f"{key} must have at least 12 characters and at most 72 UTF-8 bytes")
    patient = values.get("DEMO_PATIENT_EMAIL", "patient@demo.ckd.invalid").strip().lower()
    doctor = values.get("DEMO_DOCTOR_EMAIL", "doctor@demo.ckd.invalid").strip().lower()
    if patient == doctor or any(not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", email) for email in (patient, doctor)):
        raise ValueError("Demo users need distinct valid email addresses")
    appointment_date = date.fromisoformat(values.get("DEMO_APPOINTMENT_DATE", "2030-01-15")).isoformat()
    return patient, doctor, appointment_date


def post(base, path, body):
    request = Request(base + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urlopen(request, timeout=30) as response:
            return response.status
    except HTTPError as error:
        return error.code


def ensure_user(base, name, email, password):
    status = post(base, "/register", {"name": name, "email": email, "password": password})
    if status == 409:
        # Never reset an existing account or promote it without proving the configured password.
        status = post(base, "/login", {"email": email, "password": password})
        if status != 200:
            raise RuntimeError(f"Existing demo account could not authenticate (HTTP {status}); no credentials were changed")
    elif status != 201:
        raise RuntimeError(f"Demo registration failed (HTTP {status})")


def seed(env_file):
    values = read_env(env_file)
    patient, doctor, appointment_date = settings(values)
    base = "http://127.0.0.1:" + values.get("AUTH_PORT", "8081") + "/auth"
    ensure_user(base, "Demo Patient", patient, values["DEMO_PATIENT_PASSWORD"])
    ensure_user(base, "Demo Doctor", doctor, values["DEMO_DOCTOR_PASSWORD"])
    payload = {"patient": patient, "doctor": doctor, "date": appointment_date,
               "database": values.get("MONGODB_DATABASE", "CKD_JAVA"), "image": values.get("DEMO_ULTRASOUND_URL", "")}
    command = ["docker", "compose", "--project-directory", str(ROOT), "--env-file", str(env_file), "-f", str(ROOT / "docker-compose.yml"),
               "exec", "-T", "-e", "CKD_DEMO_DATA=" + json.dumps(payload), "mongo", "mongosh", "--quiet", "--file", "/dev/stdin"]
    script = (ROOT / "scripts/seed.mongo.js").read_text()
    result = subprocess.run(command, input=script, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError("Mongo demo provisioning failed; check database readiness and configured demo identities")
    print("Demo users and a sample appointment are ready. No password or token was printed.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    args = parser.parse_args()
    try:
        seed(args.env_file.resolve())
    except Exception as error:
        # Never echo HTTP bodies, subprocess output, URLs, or configuration values containing secrets.
        if isinstance(error, (ValueError, FileNotFoundError)):
            parser.exit(1, "Invalid or missing seed configuration; check .env and docs/deployment.md\n")
        parser.exit(1, "Demo seeding failed; check service readiness, credentials, and login rate limits\n")


if __name__ == "__main__":
    main()
