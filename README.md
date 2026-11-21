# CKD Platform

[![Build, tests, coverage and Docker](https://github.com/Sai-Rohan005/Chronic_Kidney_Disease_Platform/actions/workflows/tests.yaml/badge.svg)](https://github.com/Sai-Rohan005/Chronic_Kidney_Disease_Platform/actions/workflows/tests.yaml)
[![Coverage gates](https://img.shields.io/badge/coverage-JaCoCo%20gates-blue)](docs/testing.md)
[![Java 21](https://img.shields.io/badge/Java-21-blue)](pom.xml)

A research prototype for multimodal chronic kidney disease (CKD) prediction using
clinical tabular records and kidney ultrasound images. It links patients, assigned
doctors, appointments, model calls, and stored reports. The implemented pipeline
combines modality probabilities into a binary CKD/No CKD result.

> **Medical disclaimer:** This project is for research and software demonstration.
> It is **not a medical device** and must not be used to diagnose, rule out, triage,
> or treat CKD, or to replace a qualified clinician's judgment. Software tests,
> seeded examples, and model outputs do not establish clinical safety or efficacy.

## Model Evaluation Summary

The following metrics were obtained during offline training and evaluation.
They are **research results only** and have not been validated in a clinical setting.

### Image-Based Model (TransUNet + ROI + ResNet50)

| Metric     | Score  |
|------------|--------|
| Precision  | 0.95   |
| Recall     | 0.94   |
| F1-Score   | 0.94   |
| ROC-AUC    | 0.99   |
| Dice       | 0.918  |
| IoU        | 0.865  |

### Clinical Tabular Model (TabTransformer)

| Metric     | Score  |
|------------|--------|
| Precision  | 0.97   |
| Recall     | 0.98   |
| F1-Score   | 0.98   |
| ROC-AUC    | 0.99   |

**Dataset construction**

- **Ultrasound images**: Combined the Roboflow CKD-NJUWC dataset with selected healthy kidney samples from the Kidney Stone Ultrasound dataset to create a more balanced normal vs CKD distribution. Images were pre-processed for size standardization, denoising, and quality improvement.
- **Clinical tabular data**: Taken from the UCI CKD Repository (age, blood pressure, serum creatinine, blood urea, hemoglobin, potassium, eGFR, etc.). Controlled synthetic samples were generated to improve class balance while attempting to preserve real feature distributions. Records were anonymized and split using stratified sampling.

**Important limitations of these results**
- The UCI CKD dataset is relatively small and has been extensively studied; high scores are common in published student work.
- Synthetic tabular samples were used. The exact generation method and confirmation that no synthetic samples entered the test set should be reviewed carefully.
- Ultrasound images come from different sources and may introduce domain shift.
- Splits were stratified. Explicit patient-level exclusivity should be verified before treating the numbers as fully leakage-free.
- These metrics come from offline experiments. The microservices stack uses the trained models but does not re-validate clinical performance.

## Architecture

The backend comprises four Java 21/Spring Boot applications built with Maven.
Python/FastAPI hosts the Torch models and OCR route. React/Vite builds the frontend,
served by Nginx in Compose.

```mermaid
flowchart LR
    Browser["Browser"] --> Frontend["React + Nginx :3000"]
    subgraph APIs["Spring Boot services"]
        Auth["Auth :8081"]
        Appointment["Appointment :8082"]
        Report["Report :8083"]
        Prediction["Prediction :8084"]
    end
    subgraph Stores["Shared infrastructure"]
        Mongo[("MongoDB: CKD_JAVA")]
        Redis[("Redis: active JWT jti entries and login limits")]
    end
    subgraph Models["Python ML service :8001"]
        ML["FastAPI: tabular, ultrasound, OCR"]
    end
    Frontend --> Auth
    Frontend --> Appointment
    Frontend --> Report
    Frontend --> Prediction
    Frontend -->|"/ml/ proxy"| ML
    Auth --> Mongo
    Appointment --> Mongo
    Report --> Mongo
    Auth --> Redis
    Appointment --> Redis
    Report --> Redis
    Prediction --> Redis
    Prediction --> Appointment
    Prediction --> Report
    Prediction --> ML
    ML --> Redis
    ML -->|"HTTPS image download"| Images["Allow-listed image host"]


Prediction flow
```mermaid
sequenceDiagram
    actor Doctor
    participant Web as Frontend / Nginx
    participant P as Prediction
    participant Redis
    participant A as Appointment
    participant R as Report
    participant DB as MongoDB
    participant ML as FastAPI models

    Doctor->>Web: Submit appointment_id with Bearer token
    Web->>P: POST /predictions?force=false + X-Request-Id
    P->>Redis: Check active jti after local JWT verification
    Note right of P: Require Doctor role<br/>Normalize ID<br/>Acquire local appointment lock
    P->>A: GET /appointments/{id} (forward token + request ID)
    A->>DB: Fetch appointment and check ownership
    DB-->>A: clinical_data, ultrasound_data, assignment
    A-->>P: Appointment data
    Note right of P: Verify Doctor is assigned
    P->>R: GET /reports/{id} (forward token + request ID)
    R->>DB: Authorize against appointment and find report
    DB-->>R: Existing report or absent
    R-->>P: Report or 404

    alt Report exists and force is false
        P-->>Web: Return saved report
    else New prediction or force=true
        Note right of P: Validate clinical data<br/>and allow-listed HTTPS image URLs
        P->>ML: POST /routes/tab_predict
        ML-->>P: Tabular model response
        par Concurrent image inference
            P->>ML: POST /routes/unet_predict
            ML-->>P: Image response A
        and
            P->>ML: POST /routes/unet_predict
            ML-->>P: Other image responses
        end
        Note right of P: Validate probabilities<br/>Average images<br/>Fuse modalities<br/>Apply threshold
        alt Report existed before forced computation
            P->>R: PATCH /reports/{id}
        else No report existed
            P->>R: POST /reports
        end
        R->>DB: Authorize and persist (unique appointment_id index)
        alt Write succeeds
            DB-->>R: Stored report
            R-->>P: Report envelope
        else Concurrent create conflict (409)
            alt force=true
                P->>R: PATCH /reports/{id}
            else force=false
                P->>R: GET /reports/{id}
            end
            R->>DB: Authorize and read/update winner
            DB-->>R: Authoritative report
            R-->>P: Authoritative report
        end
        P-->>Web: Report envelope + fusion metadata
    end
    Web-->>Doctor: Display response
    Note over A,ML: Each service independently validates the forwarded JWT against Redis



API referenceBearer = Authorization: Bearer <token>. Supported roles: Patient and Doctor.
Auth — port 8081
| Method | Path | Auth | Scope |
| --- | --- | --- | --- |
| POST | /auth/register | Public | Creates Patient only |
| POST | /auth/login | Public (rate-limited) | Login |
| POST | /auth/logout | Bearer | Revoke token |
| POST | /auth/change-password | Bearer | Self |
| GET | /auth/getUserByToken | Bearer | Self |
| GET | /auth/getAllDoctors | Bearer | Doctor directory |

Appointment — port 8082
| Method | Path | Auth | Scope |
| --- | --- | --- | --- |
| POST | /appointments | Bearer | Patient books for self |
| GET | /appointments | Bearer | Own or assigned |
| GET | /appointments/{id} | Bearer | Owner or assigned Doctor |
| POST | /appointments/updateAppointment | Bearer | Owner or assigned Doctor |

Report — port 8083
| Method | Path | Auth | Scope |
| --- | --- | --- | --- |
| POST | /reports | Bearer | Assigned Doctor |
| GET | /reports/{appointmentId} | Bearer | Owner or assigned Doctor |
| PATCH | /reports/{appointmentId} | Bearer | Assigned Doctor |

Prediction — port 8084
| Method | Path | Auth | Scope |
| --- | --- | --- | --- |
| POST | /predictions?force=false | Bearer | Assigned Doctor |

ML — port 8001
| Method | Path | Auth | Scope |
| --- | --- | --- | --- |
| POST | /routes/tab_predict | Bearer | Doctor |
| POST | /routes/unet_predict | Bearer | Doctor |
| POST | /routes/extract_text | Bearer | Doctor |

Security model
Shared JWT filter + Redis-backed active-token allowlist (auth:token:<jti>).
Logout immediately removes the token from Redis.
Patients can only access their own data; Doctors can only access assigned appointments/reports.
Password hashing with jBCrypt. Strong JWT_SECRET required at startup.
Login rate limiting via Redis (default 5 attempts / 60 s per IP).
No unauthenticated internal service calls. Tokens and X-Request-Id are forwarded.

Run locallyRequirements
Docker + Docker Compose
Python 3 (for secret generation and seeding)
Model files placed in the directory pointed to by ML_MODELS_PATH
1. Create environment file
cp .env.example .env
2. Generate secrets (run once)
python3 - <<'PYCONFIG'
from pathlib import Path
import base64, secrets
path = Path('.env')
text = path.read_text()
for key in ('MONGO_PASSWORD', 'REDIS_PASSWORD', 'GRAFANA_ADMIN_PASSWORD',
            'DEMO_PATIENT_PASSWORD', 'DEMO_DOCTOR_PASSWORD'):
    text = text.replace(key + '=\n', key + '=' + secrets.token_hex(24) + '\n')
text = text.replace('JWT_SECRET=\n', 'JWT_SECRET=' + base64.b64encode(secrets.token_bytes(32)).decode() + '\n')
path.write_text(text)
path.chmod(0o600)
PYCONFIG

3. Place model files

Set ML_MODELS_PATH in .env to a directory that contains at least:
tab_binary_8/ordinal_encoder_fold1_5.pkl
tab_binary_8/scaler_fold1_5.pkl
tab_binary_8/tabtransformer_fold1_5_best.pth
transunet_kidney.pth
ckd_classifier_resnet50_2_2.pth


4. Start the stack
docker compose up --build -d
./scripts/seed.sh

Tests & CI
# Unit + integration tests + coverage gates
mvn clean verify

# Full Docker-based integration tests
mvn -Pdocker-integration clean verify


Known limitations
Research prototype only — not a medical device.
Tabular results use UCI CKD + synthetic samples; interpret high scores with caution.
Ultrasound data comes from multiple sources (possible domain shift).
Stratified splits were used; explicit patient-level exclusivity should be confirmed.
Model checkpoints and full training notebooks are not yet fully packaged in this repository.
Shared MongoDB across services; some frontend/backend contract gaps remain.
Booking upload lifecycle is incomplete.
Future work
Package model checkpoints + training/evaluation scripts with clear provenance.
Publish exact synthetic-data generation code and patient-level split manifests.
Resolve remaining Java ↔ Python and frontend contract mismatches.
Complete the booking/upload flow.
Explore learned fusion weights trained only on training/validation data.
Improve multi-instance coordination and observability.
Repository map

| Path | Contents |
| --- | --- |
| shared-auth/ | Shared JWT, authorization, and correlation filters |
| services/ | Four Spring Boot services + Python ML service |
| frontend/ | React + Vite + Nginx |
| integration-tests/ | Testcontainers + WireMock flows |
| infrastructure/ | Prometheus / Grafana |
| scripts/ | Seeding and tooling |
| docs/ | Deployment, observability, testing, security notes |