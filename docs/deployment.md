# Container deployment

All build contexts are the repository root. Each Java Dockerfile builds its
service and `shared-auth` with Maven, then copies only the executable jar into an
Alpine JRE 21 runtime. Runtime UID/GID is 10001, and health probes use the private
Actuator listener. Tests and JaCoCo gates run before image builds in CI.

Frontend uses a Node 22 build stage and non-root Nginx on port 8080. Its lockfile
makes `npm ci` reproducible. Nginx serves the SPA and proxies `/auth`, `/appointments`,
`/reports`, `/predictions`, and `/ml/` to Compose DNS names. It preserves/generates
`X-Request-Id`; raw access logging is disabled. Browser requests stay same-origin.
No Vite development proxy or frontend build-time secret is needed.

## Configuration and startup

Copy `.env.example` to `.env`, generate the blank secrets as shown in README,
and keep the file untracked. Mongo, Redis, Grafana, and both demo accounts have
no default password. Generate Mongo passwords as hexadecimal strings: Compose
interpolates them into the URI, so arbitrary reserved URI characters are not
supported by this local configuration. A startup error is preferable to silently
falling back to a weak shared secret. Changing Mongo's initialization password
later does not change a user already stored in its persistent volume.

```sh
docker compose --env-file .env config --quiet
docker compose --env-file .env up --build -d
docker compose ps
./scripts/seed.sh --env-file .env
```

All published application/UI ports bind to `127.0.0.1`. Mongo, Redis, and Actuator
management ports are not published. Inside the private Compose network the
management listeners bind to `0.0.0.0` for health/Prometheus access; host-launched
services retain loopback defaults. An external deployment needs its own TLS edge,
secret management, storage backups, and resource settings.

| Component | Host default | Dependency readiness |
| --- | --- | --- |
| Mongo | Not published | Authenticated database ping |
| Redis | Not published | Authenticated PING |
| Auth | 8081 | Mongo and Redis healthy |
| Appointment | 8082 | Mongo and Redis healthy |
| Report | 8083 | Mongo and Redis healthy |
| ML | 8001 | Redis healthy; model imports completed; authenticated HTTP probe |
| Prediction | 8084 | Appointment, Report, and ML healthy |
| Frontend | 3000 | All four Java APIs healthy |
| Prometheus (optional) | 9090 | Java APIs healthy |
| Grafana (optional) | 3001 | Prometheus healthy |

`depends_on: condition: service_healthy` governs initial startup, not ongoing
failover or automatic dependent restarts. Every application image defines a Docker
healthcheck; Mongo, Redis, and optional monitoring checks are declared in Compose.

## Real ML artifacts

The ML image uses Python 3.11 slim and CPU Torch wheels in a separate build stage.
Its runtime is non-root. The default Compose platform is `linux/amd64` for the
Paddle/Python binary dependency set, with Docker emulation required on ARM hosts. Native-library support on an ARM
host must be validated with the complete runtime.
The existing Python requirements are used; compatibility with the original model
training dependency versions must be verified with the actual artifacts.

Set `ML_MODELS_PATH` to a readable directory containing these nonempty files:

```text
tab_binary_8/ordinal_encoder_fold1_5.pkl
tab_binary_8/scaler_fold1_5.pkl
tab_binary_8/tabtransformer_fold1_5_best.pth
transunet_kidney.pth
ckd_classifier_resnet50_2_2.pth
```

The directory is mounted read-only at `/models`. It must exist; Compose does not
silently create an empty directory. The startup preflight gives an actionable
error for missing/empty assets. It does not certify their contents: normal model
loading must succeed too. No checkpoints or invented predictions are included.
OCR and pretrained encoder dependencies may download public assets on first start;
the writable `ml-cache` volume persists model caches. Allow time and outbound access
for initialization. Model files must come from a trusted source because some
formats deserialize executable Python objects.

ML's healthcheck creates a short-lived, random-jti Doctor probe credential in Redis,
requests the protected root route, then deletes the entry. Its TTL is 15 seconds
if cleanup is interrupted. No token is printed, no public auth bypass is added,
and readiness is not declared until real model imports have finished.

Without the real artifacts, ML and its dependents cannot become healthy. You can
still start `mongo redis auth appointment report` explicitly to inspect/seed the
API layer. Container integration tests use WireMock intentionally; that fixture
is never a production ML fallback.

## Demo seed

`seed.sh` requires Docker Compose and Python 3. It reads `.env` as data, not shell
code. The two passwords must satisfy the Auth policy. Users are registered through
Auth so BCrypt and validation remain authoritative. An existing account is not
reset; the configured password must authenticate before reuse. Mongo admin access
then promotes only the configured demo Doctor and upserts the marked appointment.
Repeated runs leave one marked demo appointment and preserve its existing contents.
Set the optional image before the first seed. Normal login rate limits still apply
to existing-account checks.

The clinical values are fictional fixture inputs, not clinical evidence or model
performance. `DEMO_ULTRASOUND_URL` is empty by default. Add a real allowed HTTPS
image if you want to request a prediction; no fake image or diagnosis is seeded.
The script prints no passwords or tokens. This is privileged local demo provisioning,
not an application endpoint or a production user-onboarding flow.

## Monitoring, CI, and lifecycle

```sh
docker compose --profile observability up --build -d
```

The profile uses ordinary Compose networking. Prometheus scrapes `auth:9081`,
`appointment:9082`, `report:9083`, and `prediction:9084`; Grafana provisions the
existing three-panel dashboard. Docker Desktop host networking is not required.
Grafana's host port defaults to 3001 to avoid the frontend's port 3000.

GitHub Actions runs Maven/Testcontainers/JaCoCo on Java 21, uploads HTML/XML coverage
and JUnit reports, tests the seed/coverage tools and lightweight ML security/readiness
code, then builds six images in a matrix. Image builds do not publish to a registry
or claim ML inference results. README's badges link to the actual workflow and
coverage documentation; no unmeasured percentage is embedded in a badge.

`docker compose down` stops the stack and preserves data. Add `--volumes` only when
you intend to delete this stack's Mongo, Redis, and monitoring data. Build contexts
exclude `.env` files, repository history, model weights, caches, and host build output.

The frontend dependency lock also updates jsPDF to the patched 4.2 line after a
production npm advisory was found. The image build generates a small fixture PDF
to check the constructor/output API used by the UI; no patient data is involved.
