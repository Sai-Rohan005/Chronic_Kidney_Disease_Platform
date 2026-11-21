# Observability

The four Spring Boot services expose Actuator health, metrics, and Prometheus.
Application APIs keep their existing JWT and ownership rules. Operational endpoints
use separate listeners (loopback for host runs, private unpublished ports in Compose) and do not accept an application token as a reason
to expose other management endpoints.

| Service | Application port | Loopback management port |
| --- | --- | --- |
| Auth | 8081 | 9081 |
| Appointment | 8082 | 9082 |
| Report | 8083 | 9083 |
| Prediction | 8084 | 9084 |

Each service exposes only GET `/actuator/health`, `/actuator/metrics` (including
individual metric names), and `/actuator/prometheus` on its management port.
Health details are hidden; dependency failures can return 503. Mongo and Redis
health checks are enabled in normal deployments. Spring's Mongo health client
uses `MONGODB_URI` and `MONGODB_DATABASE`, matching the application's server and database. Tests disable
external health contributors explicitly so they need no running databases.
Application-port `/actuator/**` requests retain JWT protection, with no management
endpoints mounted there. Non-exposed management paths are denied. Host services bind management to loopback. In Compose, it binds inside the private
container network and its ports are not published; see [deployment](deployment.md). `MANAGEMENT_SERVER_PORT` can override a service's port; it must stay
separate from the application listener, and scrape targets must be updated too.

## Correlation and privacy

`X-Request-Id` is returned on responses, including 401/403 failures, and included
as `requestId` in JSON request logs. A missing or invalid ID becomes a UUID.
Accepted IDs contain 1–64 ASCII letters, digits, underscores, or hyphens. This
rejects header injection, email addresses, and JWT-shaped values. Clients must
use opaque IDs without patient information. IDs are diagnostic metadata, not an
authentication mechanism.

Prediction carries the same ID through image and outbound worker pools, retries,
and calls to Appointment, Report, and both ML routes. Worker MDC is restored after
each task. The ML app wraps its complete ASGI stack so authentication failures
and errors retain the correlation header and context. Its image-storage download
does not forward caller tokens or correlation headers to public storage.

Java console logging uses Spring Boot's Logstash JSON formatter, with a `service`
field. Request completion events contain only method, HTTP status, and the
correlation ID. They exclude raw paths, query strings, bodies, clinical data,
image URLs, emails, token headers, and exception messages. Application exception
logs retain the exception class only. Stack traces, verbose Spring web diagnostics,
and raw Tomcat HTTP parse logging are suppressed; the startup banner and unused
Boot generated-password authentication are disabled. Do not enable HTTP wire,
request-body, or payload logging in deployed environments.

Python logs use a formatter that emits only approved event names and operational
fields; arbitrary messages and exception text are discarded. Uvicorn raw access
logs are disabled, replaced by correlated completion events. Model failures log
`model_failure` without input data or exception details.

## Metrics

Every service publishes `http.server.requests` with the `application` common tag.
HTTP histogram buckets are enabled so Prometheus can calculate p95 across each
service's requests. Scrape/health traffic is excluded in dashboard queries.

Prediction additionally records these Micrometer timers:

| Timer | Operation values | Scope |
| --- | --- | --- |
| `ckd.prediction.total` | `predict` | Full service invocation, including lock wait, cached results, and errors |
| `ckd.appointment.fetch` | `fetch` | Logical appointment client call, including retries/backoff |
| `ckd.model.call` | `tabular`, `image` | Each logical model invocation, including retries/backoff; one invocation per image |
| `ckd.report.store` | `create`, `update` | Report create or force-update call |

Timers have `outcome=success|error`, and histogram publication is enabled.
Returned prediction statuses >=400 and thrown exceptions record an error outcome.
Report-cache hits do not increment model/store timers. Global HTTP timers include
security rejections that occur before service invocation. Neither timer labels
nor metric names contain patient identifiers, appointment IDs, URLs, tokens,
exception messages, or correlation IDs. Prometheus exports timer names using
underscores and the `_seconds_count`, `_seconds_sum`, and `_seconds_bucket` suffixes.

## Local Prometheus and Grafana

Set up `.env` and the full Compose stack as described in [deployment](deployment.md),
then start the optional monitoring profile:

```sh
docker compose --profile observability up --build -d
```

No default Grafana password is committed. Its initial username defaults to `admin`
and can be overridden with `GRAFANA_ADMIN_USER`. Grafana persists its database;
changing the environment password after initialization does not reset an existing
administrator's password.

Prometheus and Grafana use Compose DNS names; host networking is not required.
Management ports are reachable only inside the container network.
Prometheus listens on loopback port 9090 and Grafana on loopback port 3001. Pinned
images are [Prometheus 3.16.0](https://prometheus.io/download/) and
[Grafana 13.3.0](https://grafana.com/grafana/download/13.3.0?edition=oss); configuration is bind-mounted
read-only and monitoring data uses named volumes.

Open [Grafana](http://localhost:3001), sign in, and open **CKD / CKD Services**.
The Prometheus datasource and the single dashboard JSON are provisioned automatically:

- `infrastructure/observability/prometheus.yaml`: four scrape jobs, every 15 seconds.
- `infrastructure/observability/grafana/dashboards/ckd-services.json`: request rate,
  HTTP 4xx+5xx error percentage, and histogram-based p95 latency per service.
- `infrastructure/observability/grafana/provisioning/`: datasource and dashboard provider.

The dashboard shows real samples only. It can be empty until services receive
requests and Prometheus has enough samples for `rate()`. No baseline performance
or model accuracy is implied. Dashboard provisioning follows
[Grafana's provisioning contract](https://grafana.com/docs/grafana/latest/administration/provisioning/).

Stop monitoring with `docker compose --profile observability down`. Named volumes
remain unless explicitly removed.

## Verification

`mvn test` runs correlation/privacy checks, worker propagation, client timer and
header assertions, and real HTTP Actuator contracts in every Java service. Startup checks generate their own signing keys; no configured `JWT_SECRET` is
needed for the Java test suite. These integration tests need permission to bind local sockets.
Python correlation tests load neither Torch nor model weights:

```sh
python -m unittest discover -s services/ml-service/tests -p test_observability.py
docker compose --profile observability config --quiet
```

The Docker configuration check needs `GRAFANA_ADMIN_PASSWORD` in the environment.
A running Docker daemon is needed to start the
monitoring containers; configuration validation alone does not verify a live scrape.
