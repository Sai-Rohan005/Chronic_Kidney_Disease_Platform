import base64
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class EnvSetupTest(unittest.TestCase):
    def test_readme_generates_required_secrets_in_a_private_env_file(self):
        code = re.search(r"python3 - <<'PYCONFIG'\n(.*?)\nPYCONFIG", (ROOT / "README.md").read_text(), re.S).group(1)
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / ".env"
            env.write_text((ROOT / ".env.example").read_text())
            subprocess.run([sys.executable, "-"], input=code, text=True, cwd=directory, check=True, capture_output=True)
            values = dict(line.split("=", 1) for line in env.read_text().splitlines() if line and not line.startswith("#"))
            self.assertEqual(32, len(base64.b64decode(values["JWT_SECRET"])))
            for key in ("MONGO_PASSWORD", "REDIS_PASSWORD", "GRAFANA_ADMIN_PASSWORD", "DEMO_PATIENT_PASSWORD", "DEMO_DOCTOR_PASSWORD"):
                self.assertGreaterEqual(len(values[key]), 12)
            self.assertEqual(0o600, env.stat().st_mode & 0o777)
