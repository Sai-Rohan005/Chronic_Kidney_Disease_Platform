"""Evaluation audits use fixtures only; these are not model performance experiments."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("evaluation_audit", Path(__file__).parents[1] / "evaluation/evaluate.py")
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)


class EvaluationAuditTests(unittest.TestCase):
    def test_absent_dataset_size_and_balance_are_unknown_not_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            result = evaluation.audit(Path(directory))
            self.assertEqual("blocked", result["status"])
            for dataset in result["datasets"].values():
                self.assertIsNone(dataset["sample_count"])
                self.assertIsNone(dataset["class_balance"])
                self.assertIsNone(dataset["split"])
            for model in result["results"].values():
                self.assertTrue(all(value is None for value in model.values()))

    def test_gitignored_model_artifacts_are_inventoried_without_deserializing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "services/ml-service/routes/tab_binary_8/tabtransformer_fold1_5_best.pth"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"opaque fixture; not executable model data")
            (root / ".gitignore").write_text("*.pth\n")
            subprocess.run(["git", "init", "-q", str(root)], check=True, capture_output=True)
            result = evaluation.audit(root)
            self.assertTrue(result["runtime_assets"][0]["available"])
            self.assertFalse(result["runtime_assets"][0]["tracked"])
            self.assertEqual(artifact.stat().st_size, result["runtime_assets"][0]["size_bytes"])
            self.assertEqual(64, len(result["runtime_assets"][0]["sha256"]))
            self.assertEqual("blocked", result["status"])

    def test_frontend_logos_and_seed_or_test_fixtures_are_not_study_images(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in ["frontend/public/logo.png", "services/ml-service/tests/fixture.png"]:
                path = root / relative; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(b"fixture")
            result = evaluation.audit(root)
            self.assertEqual([], result["inventory"]["study_image_candidates"])
            self.assertEqual(2, len(result["inventory"]["nonstudy_images"]))

    def test_candidate_rows_do_not_establish_a_held_out_split(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "notebooks").mkdir()
            (root / "notebooks/unverified.csv").write_text("patient_id,label\np1,1\np2,0\n")
            result = evaluation.audit(root)
            self.assertEqual(1, len(result["inventory"]["data_candidates"]))
            self.assertEqual("blocked", result["status"])
            self.assertIsNone(result["results"]["tabular_only"]["accuracy"])

    def test_learned_fusion_is_never_fit_to_test_or_synthetic_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            result = evaluation.audit(Path(directory))
            self.assertIsNone(result["fusion"]["learned_weights"])
            self.assertIsNone(result["results"]["learned_or_weighted_fusion"]["roc_auc"])

    def test_external_model_symlinks_are_not_followed(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as external:
            root = Path(directory)
            parent = root / "services/ml-service/routes"
            parent.mkdir(parents=True)
            (Path(external) / "tabtransformer_fold1_5_best.pth").write_bytes(b"out of scope")
            (parent / "tab_binary_8").symlink_to(external, target_is_directory=True)
            result = evaluation.audit(root)
            self.assertFalse(result["runtime_assets"][0]["available"])
            self.assertEqual([], result["inventory"]["artifacts"])

    def test_lfs_pointer_is_not_treated_as_a_model_binary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "services/ml-service/routes/tab_binary_8/tabtransformer_fold1_5_best.pth"
            path.parent.mkdir(parents=True)
            path.write_text("version https://git-lfs.github.com/spec/v1\noid sha256:fixture\nsize 1\n")
            result = evaluation.audit(root)
            self.assertFalse(result["runtime_assets"][0]["available"])
            self.assertTrue(result["runtime_assets"][0]["lfs_pointer"])

    def test_report_is_deterministic_and_contains_no_numeric_metric_values(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = evaluation.audit(root)
            second = evaluation.audit(root)
            self.assertEqual(json.dumps(first, sort_keys=True), json.dumps(second, sort_keys=True))
            markdown = evaluation.render(first)
            self.assertEqual(markdown, evaluation.render(second))
            self.assertIn("Not computed", markdown)
            self.assertIn("not a medical device", markdown)
            self.assertIn("patient", markdown.lower())
