# Testing and coverage

Run the deterministic Java unit, security, HTTP contract, and startup tests with
Java 21 and Maven:

```sh
mvn clean verify
```

No external MongoDB, Redis, ML process, or configured JWT secret is needed for
this mode. Tests generate signing keys and use mocked storage where appropriate.
HTTP tests bind ephemeral loopback ports. Each service's JaCoCo report is written
to `target/site/jacoco/index.html`, with XML and CSV alongside it. `verify` enforces
the coverage gates; `mvn test` runs tests without the report/check phase.

## Suite structure

| Layer | Tests and assertions |
| --- | --- |
| Authentication units | Invalid/missing email and credentials; generic wrong-password/unknown-user response; malformed stored hashes; successful normalized login; token-store failure; duplicate and concurrent duplicate registrations; unrelated database failures; registration normalization without issuing a token |
| JWT units | Issued claims and 60-minute TTL; unique IDs; expiration; missing/blank jti; missing expiry/subject/role; unsupported role; payload tampering and a different signing key; exact Redis allowlist match; Redis outage; revocation and expired-token deletion rejection |
| Prediction units | Both class probabilities must be numeric, finite, in [0,1], and sum to 1 within 1e-6; malformed/null outputs fail 502 without persistence; image mean before weighted fusion; normalized and zero weights; threshold boundary; rounding tolerance; saved-report provenance |
| HTTP/security contracts | Actual controllers and filter chains, no/expired/revoked token, wrong role, cross-patient and unassigned-doctor access; Actuator management isolation and JSON log privacy |
| WireMock client/flow tests | Route/method/body/header contracts, timeouts, transient failures, malformed JSON, circuit open, partial image failures, duplicates, force, token/request-ID propagation, and stage timers |
| Container integration/E2E | Real MongoDB and Redis plus all four executable Java service jars, with WireMock replacing only the external ML service |

The new focused units contribute 64 parameterized/test invocations (21 Auth,
20 JWT, 23 fusion). Inputs and model probabilities are controlled fixtures, not
clinical observations or measured model performance. The four empty
`contextLoads()` classes were removed: every application's real-HTTP
`ActuatorContractTest` already verifies startup, security configuration, endpoint
exposure, metric output, and log privacy.

The fusion validation tests exposed accepted malformed pairs and a null-result
exception. The service now rejects those as 502; default weights and threshold
remain unchanged.

## Testcontainers: full service flows

Start Docker, then run from the repository root:

```sh
mvn -Pdocker-integration clean verify
```

The profile adds `integration-tests/`, runs Failsafe during integration-test/verify,
and fails if Docker is unavailable. It does not silently skip the requested tests.
Allow up to several minutes for the first public-image downloads. CI runs this
same command on Java 21 in `.github/workflows/tests.yaml`.

The test network includes isolated MongoDB 8.0.16, Redis 7.4.2, and WireMock 3.13.2.
The four service jars are built first, then started in separate Java 21 containers.
Keeping them in separate JVMs also avoids their existing overlapping package names.
Testcontainers 1.21.4 is pinned for compatibility with current Docker API changes
([upstream release notes](https://github.com/testcontainers/testcontainers-java/releases/tag/1.21.4)).
Mongo driver dependencies use the same BOM as the services. Public test images
and ephemeral ports are used; no development database is accessed.

`PlatformIT` covers:

1. Register -> BCrypt hash persisted -> login -> Redis jti TTL -> token accepted ->
   logout deletes the entry -> the token is rejected by Auth and Appointment.
2. Book -> fetch persisted clinical data -> another Patient receives 403.
3. Create -> fetch a report as its Patient -> repeat creation returns 409 -> a
   direct duplicate insert is rejected by Mongo's real unique index.
4. Assigned Doctor predicts through the real Appointment/Report clients and mocked
   ML HTTP routes -> expected fusion is persisted -> repeated prediction reuses
   the report without another ML invocation. Patient prediction receives 403.
5. Partial ML failure returns 502 and leaves the reports collection empty.

Doctor accounts are explicitly promoted in Mongo as test provisioning before
login, rather than granting Doctor through public registration. A fresh signing
key is generated per suite. Mongo data is reset between scenarios without dropping
indexes; containers, clients, and the test network are cleaned up after the run.

The E2E image URLs are seeded storage fixtures. The suite does not call Cloudinary
or claim to test its upload/completion path, which is currently commented out in
AppointmentService. It verifies booking/fetch, then seeds a completed image-bearing
appointment for prediction. No model weights or Python/Torch dependencies are
required for these Java integration tests.

## Coverage baseline and ratchet

The baseline is measured from a clean deterministic Java run, including WireMock
and real HTTP startup tests. All production classes count; there are no coverage
exclusions. Remote container JVM execution is not merged into this report, so real
repository behavior is verified by container tests without inflating the local
unit/HTTP coverage measurement.

| Module | Measured line coverage | Line gate | Measured branch coverage | Branch gate |
| --- | ---: | ---: | ---: | ---: |
| shared-auth | 72.19% | 72% | 56.25% | 56% |
| Auth | 65.99% | 65% | 67.19% | 67% |
| Appointment | 27.01% | 27% | 17.65% | 17% |
| Prediction | 94.23% | 94% | 70.52% | 70% |
| Report | 50.34% | 50% | 40.00% | 40% |

These are regression floors at the current level, not desired end-state targets.
The low Appointment/Auth floors retain uncovered database and legacy paths in
the denominator. They should rise with meaningful tests, rather than by excluding
production code or setting an arbitrary threshold the build cannot yet meet.
Each independent Maven project stores its line and branch floor in its own pom.

After adding tests and completing a successful clean verify build:

```sh
python3 scripts/ratchet_coverage.py          # Show measured floors and potential increases
python3 scripts/ratchet_coverage.py --write  # Apply increases only; review the pom diff
python3 -m unittest discover -s scripts/tests -v
```

The script rounds measured ratios down to whole percentages and never lowers a
checked-in floor. Missing or empty coverage counters are errors. Read reports
only from a successful clean run; the script does not certify stale build outputs.
Do not reduce thresholds to make regressions pass. JaCoCo's gate was also tested
with an intentionally impossible 100% line requirement and correctly failed.
