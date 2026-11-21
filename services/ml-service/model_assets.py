"""Preflight artifacts without importing Torch or downloading external models."""
import os
from pathlib import Path

REQUIRED_ASSETS = (
    "tab_binary_8/ordinal_encoder_fold1_5.pkl",
    "tab_binary_8/scaler_fold1_5.pkl",
    "tab_binary_8/tabtransformer_fold1_5_best.pth",
    "transunet_kidney.pth",
    "ckd_classifier_resnet50_2_2.pth",
)


def validate_assets(directory=None):
    root = Path(directory) if directory is not None else Path(os.getenv("ML_MODELS_DIR", Path(__file__).parent / "routes"))
    if any(not (root / name).is_file() or (root / name).stat().st_size == 0 for name in REQUIRED_ASSETS):
        raise RuntimeError("Required model assets are missing or empty; mount ML_MODELS_DIR (see docs/deployment.md)")
