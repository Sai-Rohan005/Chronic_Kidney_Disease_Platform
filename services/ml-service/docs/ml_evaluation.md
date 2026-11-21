# ML evaluation: evidence audit and blocked held-out evaluation

**Status: blocked. No held-out performance metrics were computed.**

This generated report records what is available, rather than estimating missing results. The machine-readable companion is `ml_evaluation.json`; all uncomputed metric values are null.

## Reproduce

From the repository root:

```sh
make eval
make test-eval
```

Or run `make -C services/ml-service eval`. Only Python 3.11+ standard library and Make are needed; Git is optional for tracking metadata. The command regenerates both report files and deliberately exits with status 2 because clinical evaluation is blocked. The audit does not load pickle/checkpoint files, import the serving app, download models/data, query MongoDB, execute notebooks, or substitute synthetic/WireMock/demo predictions. Identical inputs produce identical report bytes.

## Dataset documentation

| Dataset/cohort | Source | Size and patient count | Class balance | Train/validation/test split |
| --- | --- | --- | --- | --- |
| Tabular CKD | Not documented | Unknown | Unknown | Not supplied |
| Ultrasound | Not documented | Unknown | Unknown | Not supplied |
| Paired multimodal | Not documented | Unknown | Unknown | Not supplied |

Filesystem discovery: **0 data/manifest candidates**, **0 notebooks**, and **0 study-image candidates**. Excluded **2 non-study image files** (UI assets or test fixtures). These are file counts, not numbers of clinical observations. Missing sample counts or class balances are unknown, not zero.

No dataset origin, citation, license, acquisition protocol, or split can be established from the available evidence. Feature names resembling a familiar public dataset do not establish its identity or size. Checkpoint names containing 'fold' do not establish a held-out split. A scaler/encoder is not a replacement for the original records and split provenance.

### Data/manifest candidates (not accepted as held-out data)

None found in the audit scope.

### Notebooks

None found in the audit scope.

### Study-image candidates

None found in the audit scope.

### Excluded non-study images

- `frontend/public/logo.png`
- `frontend/src/assets/logo.png`

### Archives (not extracted)

None found in the audit scope.

## Models and preprocessing

The current filesystem does contain tabular model/preprocessing artifacts, including ignored files. Presence and hashes below do not certify loadability, training data, preprocessing-fit boundaries, or clinical validity. 'Local only' files are not guaranteed to be present in another Git clone. LFS pointers are not usable model binaries.

### Runtime artifacts referenced by source

| Role | Repository path | Nonempty file, not LFS pointer | Git tracked | Bytes | SHA-256 |
| --- | --- | --- | --- | --- | --- |
| Tabular classifier | `services/ml-service/routes/tab_binary_8/tabtransformer_fold1_5_best.pth` | Yes | Yes | `913490` | `29e30e832b22dcd3e6bd584b01f9e2e62a6d9601e2fba8dff3f2e562db9afd9e` |
| Tabular categorical encoder | `services/ml-service/routes/tab_binary_8/ordinal_encoder_fold1_5.pkl` | Yes | Yes | `2871` | `1d47bbea8edbd9b10b2f2dc6d30a5f313e85d44ea349d839fd4a6f817f3ff6a2` |
| Tabular numeric scaler | `services/ml-service/routes/tab_binary_8/scaler_fold1_5.pkl` | Yes | Yes | `1271` | `225aea4da89ec37cb7201c6bbd33186f0f53dd6ed3c29f30e8004569d80e0955` |
| Image ROI / TransUNet | `services/ml-service/routes/transunet_kidney.pth` | No | Local only | Unknown | Unknown |
| Image classifier / ResNet | `services/ml-service/routes/ckd_classifier_resnet50_2_2.pth` | No | Local only | Unknown | Unknown |

### All discovered checkpoint/preprocessor artifacts

Copies with the same hash are not independent experiments. No artifact is selected merely because its filename sounds newer.

| Repository path | Git tracked | Bytes | SHA-256 |
| --- | --- | --- | --- |
| `services/ml-service/routes/ckd_tabtransformer_artifacts_7/ckd_tabtransformer_artifacts_9/label_encoder.pkl` | Yes | 296 | `1a094ec8c9430c5eb87ced380a888739c9d67dfe50defde3cbed450dad5ce476` |
| `services/ml-service/routes/ckd_tabtransformer_artifacts_7/ckd_tabtransformer_artifacts_9/ordinal_encoder.pkl` | Yes | 1218 | `f995fa142b1e32d6a279d6ed45fdefd5c2a75329bf47bb0c464674eb20f92daf` |
| `services/ml-service/routes/ckd_tabtransformer_artifacts_7/ckd_tabtransformer_artifacts_9/scaler.pkl` | Yes | 1244 | `58b9768195a5a5cf60774e4422bc92086c29b8080461f71058ae800159d20cd9` |
| `services/ml-service/routes/ckd_tabtransformer_artifacts_7/ckd_tabtransformer_artifacts_9/tabtransformer.pt` | Yes | 915497 | `5bf6af4db8c7b18afa8303489fb160dc9d4c2f9fd74731c361d85cd88ebd1085` |
| `services/ml-service/routes/ckd_tabtransformer_artifacts_7/label_encoder.pkl` | Yes | 306 | `885ce23957c6e1653583bc0959129d31aa0dbb53ea85c6e74df8db0d2e19e854` |
| `services/ml-service/routes/ckd_tabtransformer_artifacts_7/ordinal_encoder.pkl` | Yes | 1218 | `f995fa142b1e32d6a279d6ed45fdefd5c2a75329bf47bb0c464674eb20f92daf` |
| `services/ml-service/routes/ckd_tabtransformer_artifacts_7/scaler.pkl` | Yes | 1244 | `fface3f7e755bc159effe4b66a8863c5d3315ea9dac5f48ec3c2180436824584` |
| `services/ml-service/routes/ckd_tabtransformer_artifacts_7/tabtransformer.pt` | Yes | 916521 | `8b58f81277708af27b7b8e3fa501c7523f4e78038cdabb7f1b7e4648e92cbffe` |
| `services/ml-service/routes/mdls/tab_binary_8/tabtransformer_ckd.pt` | Yes | 913949 | `6f3a8b880a00587353eb87e87dd913aabd1bfc954b2057973107428c4ed74296` |
| `services/ml-service/routes/mdls/tab_binary_8/tabtransformer_encoders.pkl` | Yes | 2116 | `6dfde2a671bf927dddea54870b9c4d9b5ce5d705d2fa867732154454ef513152` |
| `services/ml-service/routes/tab_binary_8/ordinal_encoder_fold1_5.pkl` | Yes | 2871 | `1d47bbea8edbd9b10b2f2dc6d30a5f313e85d44ea349d839fd4a6f817f3ff6a2` |
| `services/ml-service/routes/tab_binary_8/scaler_fold1_5.pkl` | Yes | 1271 | `225aea4da89ec37cb7201c6bbd33186f0f53dd6ed3c29f30e8004569d80e0955` |
| `services/ml-service/routes/tab_binary_8/tabtransformer_ckd.pt` | Yes | 913949 | `6f3a8b880a00587353eb87e87dd913aabd1bfc954b2057973107428c4ed74296` |
| `services/ml-service/routes/tab_binary_8/tabtransformer_encoders.pkl` | Yes | 2116 | `6dfde2a671bf927dddea54870b9c4d9b5ce5d705d2fa867732154454ef513152` |
| `services/ml-service/routes/tab_binary_8/tabtransformer_fold1_5_best.pth` | Yes | 913490 | `29e30e832b22dcd3e6bd584b01f9e2e62a6d9601e2fba8dff3f2e562db9afd9e` |
| `services/ml-service/routes/tabtransformernewmodel/ordinal_encoder_fold1_5.pkl` | Yes | 2871 | `1d47bbea8edbd9b10b2f2dc6d30a5f313e85d44ea349d839fd4a6f817f3ff6a2` |
| `services/ml-service/routes/tabtransformernewmodel/ordinal_encoder_fold_5.pkl` | Yes | 2871 | `1d47bbea8edbd9b10b2f2dc6d30a5f313e85d44ea349d839fd4a6f817f3ff6a2` |
| `services/ml-service/routes/tabtransformernewmodel/scaler_fold1_5.pkl` | Yes | 1271 | `225aea4da89ec37cb7201c6bbd33186f0f53dd6ed3c29f30e8004569d80e0955` |
| `services/ml-service/routes/tabtransformernewmodel/scaler_fold_5.pkl` | Yes | 1271 | `225aea4da89ec37cb7201c6bbd33186f0f53dd6ed3c29f30e8004569d80e0955` |
| `services/ml-service/routes/tabtransformernewmodel/tabtransformer_fold1_5_best.pth` | Yes | 913490 | `29e30e832b22dcd3e6bd584b01f9e2e62a6d9601e2fba8dff3f2e562db9afd9e` |
| `services/ml-service/routes/tabtransformernewmodel/tabtransformer_fold_5_best.pth` | Yes | 913413 | `a550c834abb36f8eaa1e41415b4391fd6e361c96897b3966cb6a9d6ee23e45ee` |

### Label and preprocessing compatibility

The active tabular route declares 10 categorical and 14 numerical input features. The binary training-label mapping is not established by those declarations. The image source declares `{1: 'ckd', 0: 'notckd'}`; this does not validate its checkpoint's training labels or dataset provenance.

Other metadata files describe different stage-classification tasks; they must not be silently treated as binary CKD classifiers:

| Metadata path | Declared classes | Class count |
| --- | --- | --- |
| `services/ml-service/routes/ckd_tabtransformer_artifacts_7/ckd_tabtransformer_artifacts_9/meta.json` | `['No_Disease', 'Stage_1', 'Stage_2', 'Stage_3', 'Stage_4']` | 5 |
| `services/ml-service/routes/ckd_tabtransformer_artifacts_7/meta.json` | `['No_Disease', 'Stage_1', 'Stage_2', 'Stage_3', 'Stage_4', 'Stage_5']` | 6 |

## Requested held-out comparisons

All four comparisons require labeled, genuinely held-out cases. Fair fusion comparisons additionally require the same patients/cases in both modalities; unrelated datasets cannot be joined by row order or disease label.

| Comparison | Accuracy | Precision | Recall | F1 | ROC-AUC | PR-AUC | Confusion matrix | Brier score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Tabular only | Not computed | Not computed | Not computed | Not computed | Not computed | Not computed | Not computed | Not computed |
| Image only | Not computed | Not computed | Not computed | Not computed | Not computed | Not computed | Not computed | Not computed |
| Fusion (mean) | Not computed | Not computed | Not computed | Not computed | Not computed | Not computed | Not computed | Not computed |
| Learned/weighted fusion | Not computed | Not computed | Not computed | Not computed | Not computed | Not computed | Not computed | Not computed |

Source defaults (not evaluation results): tabular weight `0.5`, image weight `0.5`, threshold `0.5`. The service averages image CKD probabilities before normalized weighted modality fusion. These equal default weights implement the existing mean, not a learned model or a validated optimal threshold.

No learned weights or calibration parameters were fitted. No threshold, fusion weight, or preprocessing step may be selected using held-out test labels. A learned/weighted comparison needs a separate validation/training cohort and a frozen estimator before test scoring. Binary label ordering and whether PR-AUC means average precision or trapezoidal PR area must be specified before scoring; none is being claimed here. The Brier score requires real ground truth and predicted probabilities; it cannot be inferred from model files.

## Leakage and limitations

- **Patient-level ultrasound leakage cannot be audited.** No patient/case IDs, image inventory, or split manifest is supplied. Multiple scans, crops, augmented copies, and repeat visits from the same patient must stay in the same split; exact/near duplicates and patient overlap must be checked before asserting a held-out result. Absence of evidence is not evidence of no leakage.
- Split patients before augmentation, fit tabular imputers/encoders/scalers on training data only, and do not refit them on validation/test cases. Existing preprocessor files have no supplied fit-history provenance.
- **Dataset size and representativeness are unknown.** No sample-level confidence intervals, class balance, subgroup estimates, or reliable generalization claim can be produced from missing records.
- **Domain shift is unmeasured:** hospitals, scanner vendors/settings, acquisition protocols, demographics, referral patterns, CKD definitions, and prevalence can differ from any original training population. External validation and calibration cannot be assumed.
- This is a research prototype, **not a medical device**. There is no demonstrated clinical safety, efficacy, or suitability for diagnosis/treatment decisions. Software integration tests and fictional seed cases are not clinical validation.

## Blocking evidence and next required inputs

- No verified labeled held-out cohort, split manifest, training provenance, or binary label mapping is supplied.
- No matched tabular/image test cohort with shared patient/case identifiers is supplied.
- Patient disjointness, duplicates, augmentation leakage, and preprocessor fit boundaries cannot be checked.
- No validation cohort or validated learned-fusion weights are supplied; fitting on test cases is forbidden.
- At least one runtime model/preprocessing artifact is missing, empty, or only an LFS pointer.

To enable a real evaluator, supply the original dataset source/citation and local records; pseudonymous patient/case IDs and a fixed train/validation/test manifest; verified ground-truth semantics and binary label order; paired modality links for fusion; the complete compatible image pipeline checkpoints; and training/preprocessing provenance. Do not invent a split from checkpoint names or train a replacement model and present it as evaluation of these artifacts. Candidate files added later require review; this readiness auditor deliberately does not auto-accept them or auto-train a fusion model.

## Source fingerprints

The companion JSON records source literals, all discovered artifact hashes, and null metrics. The runtime sources used to establish the documented defaults and artifact paths are:

- `services/ml-service/routes/tab_trans.py`: SHA-256 `1396f52331cd58fa6eed8a8138640e379a2556631686b17742b2ebb4338dc810`
- `services/ml-service/routes/image_classification.py`: SHA-256 `6c7ea61e0f7ecfd1327ba90544413a11cdb3c9b78402bbbc4485ba250df8f3bb`
- `services/ml-service/model_assets.py`: SHA-256 `8bb71e8d16bcdd2e55f1ced980ccabba801c1f213dafef3ccbd4c8b663077cc7`
- `services/prediction-service/src/main/java/services/PredictionSettings.java`: SHA-256 `07183ad87c5b3e51514093f3c3371cbdac0024c70a6df4c56891c2aab9fd53ad`
- `services/prediction-service/src/main/java/services/CkdPredictionService.java`: SHA-256 `17b73830cfa4b24f2cf4227a5d5627c16dc65769f4915a52c80ade3e0c9d5cf0`
