import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("model_assets", Path(__file__).parents[1] / "model_assets.py")
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)


class AssetTests(unittest.TestCase):
    def test_missing_models_fail_with_an_actionable_error(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "ML_MODELS_DIR"):
                assets.validate_assets(Path(directory))

    def test_all_required_nonempty_artifacts_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in assets.REQUIRED_ASSETS:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"test fixture, not a model")
            assets.validate_assets(root)
            (root / assets.REQUIRED_ASSETS[0]).write_bytes(b"")
            with self.assertRaises(RuntimeError):
                assets.validate_assets(root)
