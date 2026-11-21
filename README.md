# CKD Platform

[![Build, tests, coverage and Docker](https://github.com/Sai-Rohan005/Chronic_Kidney_Disease_Platform/actions/workflows/tests.yaml/badge.svg)](https://github.com/Sai-Rohan005/Chronic_Kidney_Disease_Platform/actions/workflows/tests.yaml)
[![Coverage gates](https://img.shields.io/badge/coverage-JaCoCo%20gates-blue)](docs/testing.md)
[![Java 21](https://img.shields.io/badge/Java-21-blue)](pom.xml)

A multimodal CKD platform with Spring Boot Auth, Appointment, Report, and Prediction
services; MongoDB and Redis; Python/FastAPI ML; and a React frontend.

## Local stack

Requirements: Docker with Compose, Python 3 for seeding, and the real ML artifacts
listed in [deployment](docs/deployment.md). The production image never substitutes
mock predictions for missing models.

```sh
cp .env.example .env
```

Generate the blank local secrets without printing them:

```sh
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
```

Set `ML_MODELS_PATH` in `.env`, then:

```sh
docker compose config --quiet
docker compose up --build -d
./scripts/seed.sh
```

Open [the frontend](http://localhost:3000). Demo login emails and generated passwords
are configured in your private `.env`; no real credentials are committed. A seeded
appointment has fictional clinical inputs and no image unless you supply
`DEMO_ULTRASOUND_URL`. Prediction still requires a valid image and loaded models.

Start optional monitoring with `docker compose --profile observability up -d`.
[Grafana](http://localhost:3001) and [Prometheus](http://localhost:9090) use the
provisioned dashboard. Without model files you can start only the API dependencies:
`docker compose up --build -d mongo redis auth appointment report`.

## Tests and coverage

```sh
mvn clean verify                         # Unit/security/HTTP tests and JaCoCo gates
mvn -Pdocker-integration clean verify    # Also real Mongo/Redis and WireMock E2E
python3 -m unittest discover -s scripts/tests -v
```

CI runs the full container suite, publishes coverage artifacts, and builds all six
application images. [Testing and measured coverage baselines](docs/testing.md),
[deployment and healthchecks](docs/deployment.md),
[observability](docs/observability.md), and
[token-validation ADR](docs/adr/001-token-validation.md) describe the contracts and
operational assumptions.

## Repository

- `shared-auth/`: common JWT, authorization, and correlation filters.
- `services/`: four Java services and Python ML service, each with a Dockerfile.
- `frontend/`: React/Vite build and non-root Nginx image.
- `infrastructure/`: Prometheus and Grafana provisioning.
- `scripts/`: demo seed and coverage ratchet tooling.
