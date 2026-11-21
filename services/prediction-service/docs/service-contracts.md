# Service HTTP contracts

These are the canonical contracts used by Prediction and the Appointment and
Report controllers. All listed routes require the caller's Bearer token;
only Auth's login and registration POSTs are public. The shared auth module
checks JWT signatures and Redis revocation, and service methods enforce ownership.
See `../../../docs/adr/001-token-validation.md` for the security policy and build requirements.
Configure service hosts through environment variables; do not add endpoint suffixes
to the collection URLs below.

| Operation | Method and route | Request | Success response |
| --- | --- | --- | --- |
| Fetch appointment | `GET :8082/appointments/{id}` | MongoDB ObjectId in the path; no request body | 200, `{"data":{...appointment fields...}}` |
| Fetch report | `GET :8083/reports/{appointmentId}` | Appointment ObjectId in path; no body | 200, `{"report":{...}}`; 404 if absent |
| Store report | `POST :8083/reports` | JSON object with `appointment_id` and report fields | 201, `{"message":"Report created successfully","report":{...}}` |
| List appointments | `GET :8082/appointments` | `Authorization: Bearer <token>` header | 200, `{"appointments":[...]}` |
| List images | `GET :8082/appointments/images?patient_email=...` | Required `patient_email` query parameter; no body | 200, `{"images":[...]}` |
| List uploads | `GET :8082/appointments/uploads?patient_email=...` | Required `patient_email` query parameter; no body | 200, `{"uploads":[...]}` |

Appointment's success envelope contains `clinical_data` (an object) and
`ultrasound_data` (an array of image URL strings) inside `data`. `AppointmentClient`
unwraps `data` before passing it to prediction logic, and rejects a missing or
non-object `data` as an invalid upstream response. Invalid IDs return 400; a
valid ID with no matching appointment returns 404.

Report's request uses `appointment_id` (snake case), for example:

```json
{
  "appointment_id": "507f1f77bcf86cd799439011",
  "results": {"final_diagnosis": "No CKD"}
}
```

This is an illustrative request, not a model result. Prediction also sends
`tabres` and `unetRes`; Report accepts those fields plus `reports` and `analysis`.
At least one report field is required. Report's success response remains wrapped
in `report`; `ReportClient` returns that response unchanged.

## Configuration and rollout

Prediction's `application.yaml` defaults are:

```yaml
appointment:
  appointment-url: ${APPOINTMENT_CLIENT_URL:${Appointment_client_Url:http://localhost:8082/appointments}}
report:
  report-url: ${REPORT_CLIENT_URL:${Report_client_Url:http://localhost:8083/reports}}
```

`AppointmentClient` appends the encoded appointment ID as a path segment.
`ReportClient` posts directly to the configured report URL. Update deployed
environment overrides as well as the defaults if they still contain
`getappointment` or `store_report`.

The former `POST /appointments/getAppointment` JSON-body endpoint is replaced
by GET-by-ID; `/appointments/images` and `/appointments/uploads` no longer accept
GET bodies. Deploy the updated Appointment and Prediction services together.
The separate React repo still has old appointment-detail callers in
`PatientDashboard.jsx` and `DoctorDashboard.jsx`; migrate those to GET-by-ID and
read `response.data.data` before rolling out the route removal to those screens.
Frontend changes are outside this four-service patch.

## Errors

All four services discover `services.web.ApiErrorAdvice` through their existing
component scan. It normalizes existing controller error results and handles
Spring request exceptions through `ResponseEntityExceptionHandler`:

```json
{
  "timestamp": "2026-11-18T00:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "A valid appointment ID is required",
  "path": "/appointments/not-an-id"
}
```

The timestamp above is illustrative; actual responses use the current UTC
instant. HTTP status and headers (including `Allow` on 405 responses) are
preserved. Existing service messages are retained. Framework request errors use
the HTTP reason phrase; explicit `ResponseStatusException` reasons are retained.
Unexpected exceptions return 500 and `An unexpected error occurred`; details
are logged server-side. Successful response bodies are unchanged. Auth's
interceptor now throws a status exception so its rejections use the same advice.

The advice and its contract tests are deliberately identical local files in
the four independent repositories; no new cross-repository build dependency is
introduced. Prediction also declares a prototype `RestClient.Builder` bean,
because its current dependencies do not auto-configure one.

## Verification

Consumer tests use `MockRestServiceServer` with URLs resolved from the real YAML
defaults, and assert method, URI, empty GET body, JSON POST content type, request
fields, and response envelopes. Provider tests use MockMvc with the actual
controllers and services, mocking only database/Cloudinary dependencies. They
assert the matching routes, statuses, and JSON bodies. Error-contract tests run
independently in all four services and cover service errors, malformed JSON,
missing headers, wrong methods, explicit status errors, unexpected failures,
and unchanged successes. Auth additionally tests interceptor rejection.

From the workspace root:

```bash
mvn '-Dtest=*ContractTest,ServiceClientsTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Full suites and coverage gates use `mvn verify` from the workspace root. Startup
contracts generate their own signing keys; no configured `JWT_SECRET` is needed
for tests. Runtime applications still require it. Independent
repository builds must install or publish `shared-auth` first. The focused tests need no
secrets or running MongoDB, Redis, or ML server. Mockito uses its subclass mock
maker in provider tests so they do not need JVM agent attachment.

## Prediction execution, resilience, and idempotency

`POST :8084/predictions?force=false` takes `{"appointment_id":"..."}` and
requires an assigned Doctor's token. Appointment ownership is checked before
reading or returning a saved report. IDs are normalized to lowercase ObjectId
hex strings. The existing report envelope in the prediction response is preserved.

A saved report is returned without ML calls unless `force=true`. Force computes
again and PATCHes the existing report's prediction fields. If no report exists,
it creates one. Per-appointment locks (a fixed 64-stripe table) coalesce requests
within one Prediction instance. Other instances can compute concurrently; Report's
unique Mongo index on `appointment_id` prevents multiple documents. A losing
insert gets 409; Prediction fetches the winning report, or replaces its prediction
fields for force. This guarantees unique persisted results, not exactly-once ML
execution across instances. Force requests may overwrite each other; last write
wins. There is no persisted in-progress job or background retry.

Report creates the unique index when its repository initializes, before serving
repository operations. Before deploying, inspect existing reports for duplicate,
missing, or differently cased `appointment_id` values and resolve/canonicalize them
through a reviewed migration. Index creation intentionally fails on conflicting
existing data; this patch does not delete medical reports automatically.
Deploy the Report GET route and unique index before the new Prediction version.
The older POST `/reports/getReport` remains available for existing callers.

Images run concurrently in a fixed pool, with bounded queued work. Results retain
input image order. A separate bounded outbound pool prevents image coordinators
from exhausting the workers they wait on. Each task carries the authenticated
caller, and clears its security context on exit. Queue exhaustion returns 503.
Partial image failure fails the prediction and saves no report; no diagnosis is
fabricated from missing modalities. Completed results are never substituted for
failed new inference, including a forced recomputation.

Every client uses Resilience4j TimeLimiter, Retry, and CircuitBreaker. Each attempt
has a deadline, and production HTTP transports also have connection/read deadlines.
Reads and side-effect-free ML inference POSTs may retry transient I/O/timeouts or
5xx responses with exponential waits (100ms, 200ms by default). ML retry safety
assumes the model routes remain pure inference without persistent side effects.
Report POST and PATCH writes are never automatically retried, including ambiguous
timeouts after a possible successful write. A subsequent ordinary prediction
request will find a successfully persisted report. Malformed responses and
401/403 are not retried. Authorization failures do not trip circuit breakers.
Tabular and image models have separate circuits; Appointment and Report each
have a circuit shared by their operations. Circuits use a count window and open
at 50% failures, then permit half-open probes after the open duration.

| Failure | Prediction status/fallback |
| --- | --- |
| Connection failure, timeout, circuit open, exhausted capacity, upstream 503/504 | 503, explicit temporary-unavailability error; no new report |
| Upstream 500/other server errors, malformed JSON or invalid prediction/report envelope | 502, upstream failure; no new report |
| Upstream authorization rejection | Preserve 401/403 |
| Report absent | Compute normally |
| Report insert conflict | Return the winning report (force replaces prediction fields) |

## Fusion and URL configuration

The explicit defaults preserve the prior behavior: average image CKD probabilities,
then combine tabular and image probabilities with equal weights and classify CKD
when the combined probability is **greater than or equal to 0.5**. Weights are
normalized by their sum. They must be finite and nonnegative, with a positive
sum; threshold must be finite and within [0,1]. Invalid settings fail startup.

Stored `results` now includes:

```json
{
  "modality_probabilities": {"tabular": 0.2, "image": 0.2},
  "fusion": {
    "method": "weighted_mean",
    "image_aggregation": "mean",
    "weights": {"tabular": 0.5, "image": 0.5}
  },
  "combined_probability": 0.2,
  "threshold": 0.5,
  "final_diagnosis": "No CKD"
}
```

These values are an illustrative fixture, not measured model results. Prediction
also exposes `modality_probabilities` and `fusion` at the response top level when
present in the saved report. Older reports are returned as stored; use force to
recompute with new settings rather than inventing missing provenance.

| Environment variable | Default |
| --- | --- |
| `PREDICTION_TABULAR_WEIGHT`, `PREDICTION_IMAGE_WEIGHT` | 0.5 each |
| `PREDICTION_THRESHOLD` | 0.5 |
| `PREDICTION_IMAGE_CONCURRENCY` | 4 |
| `PREDICTION_OUTBOUND_CONCURRENCY` | 8 |
| `PREDICTION_QUEUE_CAPACITY` | 32 per pool |
| `PREDICTION_TIMEOUT_MILLIS` | 30000 per attempt |
| `PREDICTION_ATTEMPTS` | 3 including the first attempt |
| `PREDICTION_BACKOFF_MILLIS` | 100, doubled per retry |
| `PREDICTION_CIRCUIT_MINIMUM_CALLS` | 10 (also the circuit window size) |
| `PREDICTION_CIRCUIT_OPEN_MILLIS` | 30000 |
| `PREDICTION_IMAGE_HOSTS` | `res.cloudinary.com` |

Ultrasound URLs require HTTPS, an exact allow-listed hostname, port 443 or no
explicit port, and no userinfo or fragment. Prefix/suffix host matching is not
used. Local paths, IP literals, HTTP, and lookalike hostnames fail before any ML
call. Configure only trusted, publicly hosted image domains whose DNS and content
you control. The Python downloader applies the same host variable at the actual
fetch, rejects redirects and local paths, and uses 3s connect/10s read timeouts.
Deploy both sides together for this defense; forwarding a validated URL to an
unrestricted downloader alone would not block redirect-based SSRF.

Focused checks from the repository root:

```sh
mvn -pl services/prediction-service,services/report-service -am '-Dtest=*Test,!*ApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false test
python -m unittest discover -s services/ml-service/tests -p test_safe_images.py
```

WireMock needs permission to bind a loopback socket. Python downloader tests
require `requests` but do not load model weights, Torch, or OCR libraries.
