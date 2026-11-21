import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("seed", Path(__file__).parents[1] / "seed.py")
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)


class SeedTests(unittest.TestCase):
    def test_env_parsing_never_executes_shell_expressions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text('DEMO_PATIENT_PASSWORD="$(touch should-not-exist)"\n')
            values = seed.read_env(path)
            self.assertEqual("$(touch should-not-exist)", values["DEMO_PATIENT_PASSWORD"])
            self.assertFalse((Path(directory) / "should-not-exist").exists())

    def test_missing_passwords_are_rejected_before_http_calls(self):
        with self.assertRaises(ValueError):
            seed.settings({})

    def test_rerun_authenticates_existing_account_without_resetting_it(self):
        with patch.object(seed, "post", side_effect=[409, 200]) as post:
            seed.ensure_user("http://test/auth", "Patient", "patient@example.invalid", "fixture-password")
            self.assertEqual(["/register", "/login"], [call.args[1] for call in post.call_args_list])

    def test_existing_wrong_password_stops_privileged_provisioning(self):
        with patch.object(seed, "post", side_effect=[409, 401]):
            with self.assertRaises(RuntimeError):
                seed.ensure_user("http://test/auth", "Patient", "patient@example.invalid", "fixture-password")
