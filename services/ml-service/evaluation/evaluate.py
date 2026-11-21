"""Audit existing evaluation evidence, without executing models or inventing data/splits.

No verified held-out cohort or inference manifest is supplied in this repository.
Accordingly this command generates a blocked report and exits 2, never metrics.
"""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

METRICS = ("accuracy", "precision", "recall", "f1", "roc_auc", "pr_auc", "confusion_matrix", "brier_score")
COMPARISONS = ("tabular_only", "image_only", "mean_fusion", "learned_or_weighted_fusion")
CHECKPOINTS = {".pt", ".pth", ".onnx", ".keras", ".safetensors", ".ckpt", ".h5", ".hdf5"}
PREPROCESSORS = {".pkl", ".pickle", ".joblib"}
DATA = {".csv", ".tsv", ".parquet", ".arff", ".jsonl", ".xlsx", ".xls", ".npz", ".npy", ".mat", ".feather"}
IMAGES = {".png", ".jpg", ".jpeg", ".tif", ".tiff", ".dcm", ".dicom", ".bmp"}
SKIP = {".git", "node_modules", "target", "dist", ".venv", "venv", "__pycache__", ".cache", ".aws", "secrets", "credentials"}
ML = Path("services/ml-service")
EXPECTED = (
    ("Tabular classifier", "routes/tab_binary_8/tabtransformer_fold1_5_best.pth"),
    ("Tabular categorical encoder", "routes/tab_binary_8/ordinal_encoder_fold1_5.pkl"),
    ("Tabular numeric scaler", "routes/tab_binary_8/scaler_fold1_5.pkl"),
    ("Image ROI / TransUNet", "routes/transunet_kidney.pth"),
    ("Image classifier / ResNet", "routes/ckd_classifier_resnet50_2_2.pth"),
)


def tracked_paths(root):
    try:
        result = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], capture_output=True, check=True)
        return set(result.stdout.decode().split("\0"))
    except (OSError, subprocess.CalledProcessError):
        return None


def fingerprint(path, root, tracked):
    relative = path.relative_to(root).as_posix()
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        prefix = handle.read(128)
        digest.update(prefix)
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"path": relative, "size_bytes": path.stat().st_size, "sha256": digest.hexdigest(),
            "tracked": relative in tracked if tracked is not None else None,
            "lfs_pointer": prefix.startswith(b"version https://git-lfs.github.com/spec/")}


def in_scope(path, root):
    return not path.is_symlink() and path.resolve().is_relative_to(root.resolve())


def source_literals(path, names, root):
    if not path.is_file() or not in_scope(path, root):
        return {}
    result = {}
    for node in ast.parse(path.read_text()).body:
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id in names:
                    try:
                        result[target.id] = ast.literal_eval(node.value)
                    except (ValueError, TypeError):
                        pass
    return result


def audit(root):
    root = root.resolve()
    tracked = tracked_paths(root)
    inventory = {name: [] for name in ("data_candidates", "notebooks", "study_image_candidates", "nonstudy_images", "artifacts", "metadata", "archives")}
    for directory, children, files in os.walk(root, followlinks=False):
        children[:] = sorted(name for name in children if name not in SKIP and not (Path(directory) / name).is_symlink())
        for name in sorted(files):
            path = Path(directory) / name
            if not in_scope(path, root) or not path.is_file():
                continue
            suffix = path.suffix.lower()
            relative = path.relative_to(root)
            parts = relative.parts
            category = None
            if suffix in DATA or (suffix == ".json" and re.search(r"(manifest|split|dataset)", name, re.I)):
                category = "data_candidates"
            elif suffix == ".ipynb":
                category = "notebooks"
            elif suffix in IMAGES:
                fixture = parts[0] in {"frontend", "infrastructure"} or "tests" in parts or ("src" in parts and "test" in parts)
                category = "nonstudy_images" if fixture else "study_image_candidates"
            elif suffix in CHECKPOINTS | PREPROCESSORS:
                category = "artifacts"
            elif name == "meta.json" and relative.is_relative_to(ML):
                category = "metadata"
            elif suffix in {".zip", ".tar", ".gz", ".7z"}:
                category = "archives"
            if category:
                entry = fingerprint(path, root, tracked)
                if category == "metadata":
                    metadata = json.loads(path.read_text())
                    entry["declared_classes"] = metadata.get("classes")
                    entry["categorical_features"] = metadata.get("categorical_cols")
                    entry["numerical_features"] = metadata.get("numerical_cols")
                inventory[category].append(entry)
    for entries in inventory.values():
        entries.sort(key=lambda item: item["path"])
    runtime = []
    for role, relative in EXPECTED:
        path = root / ML / relative
        entry = fingerprint(path, root, tracked) if path.is_file() and in_scope(path, root) else {"path": (ML / relative).as_posix(), "size_bytes": None, "sha256": None, "tracked": (ML / relative).as_posix() in tracked if tracked is not None else None, "lfs_pointer": False}
        entry.update(role=role, available=bool(entry["size_bytes"]) and not entry["lfs_pointer"])
        runtime.append(entry)
    tabular = source_literals(root / ML / "routes/tab_trans.py", {"categorical_cols", "numerical_cols"}, root)
    image = source_literals(root / ML / "routes/image_classification.py", {"CLASS_IDX_TO_NAME"}, root)
    java = root / "services/prediction-service/src/main/java/services/PredictionSettings.java"
    defaults = {}
    for field in ("tabularWeight", "imageWeight", "threshold"):
        match = re.search(r"private double " + field + r"\s*=\s*([0-9.]+);", java.read_text()) if java.is_file() and in_scope(java, root) else None
        defaults[field] = float(match[1]) if match else None
    source_paths = [ML / "routes/tab_trans.py", ML / "routes/image_classification.py", ML / "model_assets.py",
                    Path("services/prediction-service/src/main/java/services/PredictionSettings.java"),
                    Path("services/prediction-service/src/main/java/services/CkdPredictionService.java")]
    sources = [fingerprint(root / path, root, tracked) for path in source_paths if (root / path).is_file() and in_scope(root / path, root)]
    unknown_dataset = {"source": None, "sample_count": None, "patient_count": None, "class_balance": None, "split": None}
    blockers = [
        "No verified labeled held-out cohort, split manifest, training provenance, or binary label mapping is supplied.",
        "No matched tabular/image test cohort with shared patient/case identifiers is supplied.",
        "Patient disjointness, duplicates, augmentation leakage, and preprocessor fit boundaries cannot be checked.",
        "No validation cohort or validated learned-fusion weights are supplied; fitting on test cases is forbidden.",
    ]
    if any(not asset["available"] for asset in runtime):
        blockers.append("At least one runtime model/preprocessing artifact is missing, empty, or only an LFS pointer.")
    return {"schema_version": 1, "status": "blocked", "metrics_computed": False,
            "scope": "Current repository filesystem, including Git-ignored artifacts; no external data, runtime databases, or mounted model directories.",
            "inventory": inventory, "runtime_assets": runtime, "sources": sources,
            "datasets": {name: dict(unknown_dataset) for name in ("tabular", "ultrasound", "paired_multimodal")},
            "source_facts": {"tabular": tabular, "image": image},
            "fusion": {"source_defaults": defaults, "learned_weights": None},
            "results": {name: {metric: None for metric in METRICS} for name in COMPARISONS}, "blockers": blockers}


def code(value):
    return "Unknown" if value is None else "`" + str(value).replace("|", r"\|").replace("`", "") + "`"


def render(result):
    inventory = result["inventory"]
    lines = ["# ML evaluation: evidence audit and blocked held-out evaluation", "",
        "**Status: blocked. No held-out performance metrics were computed.**", "",
        "This generated report records what is available, rather than estimating missing results. "
        "The machine-readable companion is `ml_evaluation.json`; all uncomputed metric values are null.", "",
        "## Reproduce", "", "From the repository root:", "", "```sh", "make eval", "make test-eval", "```", "",
        "Or run `make -C services/ml-service eval`. Only Python 3.11+ standard library and Make are needed; "
        "Git is optional for tracking metadata. "
        "The command regenerates both report files and deliberately exits with status 2 because clinical evaluation is blocked. "
        "The audit does not load pickle/checkpoint files, import the serving app, download models/data, query MongoDB, "
        "execute notebooks, or substitute synthetic/WireMock/demo predictions. Identical inputs produce identical report bytes.", "",
        "## Dataset documentation", "",
        "| Dataset/cohort | Source | Size and patient count | Class balance | Train/validation/test split |",
        "| --- | --- | --- | --- | --- |",
        *[f"| {label} | Not documented | Unknown | Unknown | Not supplied |" for label in ("Tabular CKD", "Ultrasound", "Paired multimodal")], "",
        f"Filesystem discovery: **{len(inventory['data_candidates'])} data/manifest candidates**, "
        f"**{len(inventory['notebooks'])} notebooks**, and **{len(inventory['study_image_candidates'])} study-image candidates**. "
        f"Excluded **{len(inventory['nonstudy_images'])} non-study image files** (UI assets or test fixtures). "
        "These are file counts, not numbers of clinical observations. Missing sample counts or class balances are unknown, not zero.", "",
        "No dataset origin, citation, license, acquisition protocol, or split can be established from the available evidence. "
        "Feature names resembling a familiar public dataset do not establish its identity or size. "
        "Checkpoint names containing 'fold' do not establish a held-out split. "
        "A scaler/encoder is not a replacement for the original records and split provenance.", ""]
    for kind, title in (("data_candidates", "Data/manifest candidates (not accepted as held-out data)"),
                        ("notebooks", "Notebooks"), ("study_image_candidates", "Study-image candidates"),
                        ("nonstudy_images", "Excluded non-study images"), ("archives", "Archives (not extracted)")):
        lines.extend([f"### {title}", ""])
        lines.extend([f"- {code(item['path'])}" for item in inventory[kind]] or ["None found in the audit scope."])
        lines.append("")
    lines.extend(["## Models and preprocessing", "",
        "The current filesystem does contain tabular model/preprocessing artifacts, including ignored files. "
        "Presence and hashes below do not certify loadability, training data, preprocessing-fit boundaries, or clinical validity. "
        "'Local only' files are not guaranteed to be present in another Git clone. LFS pointers are not usable model binaries.", "",
        "### Runtime artifacts referenced by source", "",
        "| Role | Repository path | Nonempty file, not LFS pointer | Git tracked | Bytes | SHA-256 |",
        "| --- | --- | --- | --- | --- | --- |"])
    for asset in result["runtime_assets"]:
        tracked = "Unknown" if asset["tracked"] is None else "Yes" if asset["tracked"] else "Local only"
        lines.append(f"| {asset['role']} | {code(asset['path'])} | {'Yes' if asset['available'] else 'No'} | {tracked} | {code(asset['size_bytes'])} | {code(asset['sha256'])} |")
    lines.extend(["", "### All discovered checkpoint/preprocessor artifacts", "",
        "Copies with the same hash are not independent experiments. No artifact is selected merely because its filename sounds newer.", "",
        "| Repository path | Git tracked | Bytes | SHA-256 |", "| --- | --- | --- | --- |"])
    for asset in inventory["artifacts"]:
        tracked = "Unknown" if asset["tracked"] is None else "Yes" if asset["tracked"] else "Local only"
        lines.append(f"| {code(asset['path'])} | {tracked} | {asset['size_bytes']} | {code(asset['sha256'])} |")
    if not inventory["artifacts"]:
        lines.append("| None discovered | Unknown | Unknown | Unknown |")
    facts = result["source_facts"]["tabular"]
    categorical_count = len(facts["categorical_cols"]) if "categorical_cols" in facts else "Unknown"
    numerical_count = len(facts["numerical_cols"]) if "numerical_cols" in facts else "Unknown"
    lines.extend(["", "### Label and preprocessing compatibility", "",
        f"The active tabular route declares {categorical_count} categorical and "
        f"{numerical_count} numerical input features. "
        "The binary training-label mapping is not established by those declarations. "
        f"The image source declares {code(result['source_facts']['image'].get('CLASS_IDX_TO_NAME'))}; "
        "this does not validate its checkpoint's training labels or dataset provenance.", "",
        "Other metadata files describe different stage-classification tasks; they must not be silently treated as binary CKD classifiers:", "",
        "| Metadata path | Declared classes | Class count |", "| --- | --- | --- |"])
    for item in inventory["metadata"]:
        classes = item.get("declared_classes")
        lines.append(f"| {code(item['path'])} | {code(classes)} | {len(classes) if isinstance(classes, list) else 'Unknown'} |")
    if not inventory["metadata"]:
        lines.append("| None discovered | Unknown | Unknown |")
    defaults = result["fusion"]["source_defaults"]
    lines.extend(["", "## Requested held-out comparisons", "",
        "All four comparisons require labeled, genuinely held-out cases. Fair fusion comparisons additionally require "
        "the same patients/cases in both modalities; unrelated datasets cannot be joined by row order or disease label.", "",
        "| Comparison | Accuracy | Precision | Recall | F1 | ROC-AUC | PR-AUC | Confusion matrix | Brier score |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"])
    names = {"tabular_only": "Tabular only", "image_only": "Image only", "mean_fusion": "Fusion (mean)", "learned_or_weighted_fusion": "Learned/weighted fusion"}
    for comparison in COMPARISONS:
        lines.append("| " + names[comparison] + " | " + " | ".join("Not computed" for _ in METRICS) + " |")
    lines.extend(["",
        f"Source defaults (not evaluation results): tabular weight {code(defaults['tabularWeight'])}, "
        f"image weight {code(defaults['imageWeight'])}, threshold {code(defaults['threshold'])}. "
        "The service averages image CKD probabilities before normalized weighted modality fusion. "
        "These equal default weights implement the existing mean, not a learned model or a validated optimal threshold.", "",
        "No learned weights or calibration parameters were fitted. No threshold, fusion weight, or preprocessing step "
        "may be selected using held-out test labels. A learned/weighted comparison needs a separate validation/training "
        "cohort and a frozen estimator before test scoring. Binary label ordering and whether PR-AUC means average "
        "precision or trapezoidal PR area must be specified before scoring; none is being claimed here. "
        "The Brier score requires real ground truth and predicted probabilities; it cannot be inferred from model files.", "",
        "## Leakage and limitations", "",
        "- **Patient-level ultrasound leakage cannot be audited.** No patient/case IDs, image inventory, or split manifest "
        "is supplied. Multiple scans, crops, augmented copies, and repeat visits from the same patient must stay in the "
        "same split; exact/near duplicates and patient overlap must be checked before asserting a held-out result. "
        "Absence of evidence is not evidence of no leakage.",
        "- Split patients before augmentation, fit tabular imputers/encoders/scalers on training data only, and do not "
        "refit them on validation/test cases. Existing preprocessor files have no supplied fit-history provenance.",
        "- **Dataset size and representativeness are unknown.** No sample-level confidence intervals, class balance, "
        "subgroup estimates, or reliable generalization claim can be produced from missing records.",
        "- **Domain shift is unmeasured:** hospitals, scanner vendors/settings, acquisition protocols, demographics, "
        "referral patterns, CKD definitions, and prevalence can differ from any original training population. "
        "External validation and calibration cannot be assumed.",
        "- This is a research prototype, **not a medical device**. There is no demonstrated clinical safety, efficacy, "
        "or suitability for diagnosis/treatment decisions. Software integration tests and fictional seed cases are "
        "not clinical validation.", "", "## Blocking evidence and next required inputs", ""])
    lines.extend(["- " + blocker for blocker in result["blockers"]])
    lines.extend(["", "To enable a real evaluator, supply the original dataset source/citation and local records; "
        "pseudonymous patient/case IDs and a fixed train/validation/test manifest; verified ground-truth semantics and "
        "binary label order; paired modality links for fusion; the complete compatible image pipeline checkpoints; "
        "and training/preprocessing provenance. Do not invent a split from checkpoint names or train a replacement "
        "model and present it as evaluation of these artifacts. Candidate files added later require review; this "
        "readiness auditor deliberately does not auto-accept them or auto-train a fusion model.", "",
        "## Source fingerprints", "", "The companion JSON records source literals, all discovered artifact hashes, and null metrics. "
        "The runtime sources used to establish the documented defaults and artifact paths are:", ""])
    lines.extend([f"- {code(item['path'])}: SHA-256 {code(item['sha256'])}" for item in result["sources"]])
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    output = args.output_dir or root / ML / "docs"
    result = audit(root)
    output.mkdir(parents=True, exist_ok=True)
    (output / "ml_evaluation.json").write_text(json.dumps(result, indent=2, sort_keys=True, allow_nan=False) + "\n")
    (output / "ml_evaluation.md").write_text(render(result))
    print("BLOCKED: held-out evaluation evidence is unavailable. Reports regenerated; no performance metrics computed.")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
