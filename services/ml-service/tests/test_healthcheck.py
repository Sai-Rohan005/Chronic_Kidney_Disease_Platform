import importlib.util
import sys
import types
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

package = types.ModuleType("ml_probe_test")
package.__path__ = [str(Path(__file__).parents[1])]
sys.modules["ml_probe_test"] = package
spec = importlib.util.spec_from_file_location("ml_probe_test.healthcheck", Path(__file__).parents[1] / "healthcheck.py")
health = importlib.util.module_from_spec(spec)
spec.loader.exec_module(health)


class HealthcheckTests(unittest.TestCase):
    def test_probe_requires_a_successful_authenticated_http_response_and_always_revokes(self):
        validator = Mock(key=b"x" * 32)
        response = Mock(status=200)
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        with patch.object(health.JwtValidator, "from_environment", return_value=validator), patch.object(health, "urlopen", return_value=response) as open_request:
            self.assertTrue(health.probe())
            validator.redis.set.assert_called_once()
            key, token = validator.redis.set.call_args.args
            self.assertEqual(15, validator.redis.set.call_args.kwargs["ex"])
            request = open_request.call_args.args[0]
            self.assertEqual("Bearer " + token, request.get_header("Authorization"))
            validator.redis.delete.assert_called_once_with(key)

    def test_network_failure_is_unhealthy_and_temporary_token_is_deleted(self):
        validator = Mock(key=b"x" * 32)
        with patch.object(health.JwtValidator, "from_environment", return_value=validator), patch.object(health, "urlopen", side_effect=OSError("fixture")):
            self.assertFalse(health.probe())
            validator.redis.delete.assert_called_once()

    def test_missing_secret_is_unhealthy(self):
        with patch.object(health.JwtValidator, "from_environment", side_effect=RuntimeError("missing")):
            self.assertFalse(health.probe())
