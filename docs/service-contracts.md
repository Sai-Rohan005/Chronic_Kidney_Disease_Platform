# Service HTTP contracts

These are the canonical contracts used by Prediction and the Appointment and
Report controllers. Configure service hosts through environment variables; do
not add endpoint suffixes to the collection URLs below.

| Operation | Method and route | Request | Success response |
| --- | --- | --- | --- |
| Fetch appointment | `GET :8082/appointments/{id}` | MongoDB ObjectId in the path; no request body | 200, `{"data":{...appointment fields...}}` |
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
for repo in CKD_AUTHSERVICE CKD_APPOINTMENT_SERVICE CKD_PREDICTION CKD_REPORT_SERVICE; do
  (cd "$repo" && mvn '-Dtest=*ContractTest,ServiceClientsTest' test) || exit 1
done
```

Full suites use `mvn test` in each repo; Auth's application-context test requires
a valid base64 `JWT_SECRET` from the environment. The focused tests need no
secrets or running MongoDB, Redis, or ML server. Mockito uses its subclass mock
maker in provider tests so they do not need JVM agent attachment.
