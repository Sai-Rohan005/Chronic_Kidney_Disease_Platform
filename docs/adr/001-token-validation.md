# ADR 001: Shared JWT validation and service authorization

Status: accepted. Date: 2026-11-21.

## Decision

All four Java services depend on `com.ckd:ckd-shared-auth:1.0.0-SNAPSHOT`.
The library source and tests live in `shared-auth/`; the root Maven reactor
builds it before the services. Each application explicitly imports
`SecurityAutoConfiguration`. Authentication uses a stateless `SecurityFilterChain`
with `JwtBearerFilter` (`OncePerRequestFilter`), rather than an MVC interceptor.

The filter verifies the JWT signature, expiry, subject, jti, and supported role
(`Patient` or `Doctor`). Redis must contain the exact token at `auth:token:<jti>`.
This is an allowlist: issuance stores the token with a 60-minute TTL; logout
deletes it. A missing entry or Redis failure denies authentication. The validated
email becomes the principal and the role becomes a `ROLE_` authority. The
security context is cleared after each request; sessions do not authenticate calls.

Only POST `/auth/login` and POST `/auth/register` are public, and only in Auth.
All other requests require a Bearer token, including user lookups and internal
calls. Missing, invalid, expired, or revoked tokens return 401. Authenticated
authorization failures return 403. Filter errors use
`{timestamp, status, error, message, path}`; 401 includes `WWW-Authenticate: Bearer`.
OPTIONS is protected too; browser deployments need a same-origin proxy or a
separately reviewed CORS policy.

## Direct Redis versus Auth validation endpoint

We choose local signature verification plus a Redis read on every request.
There is no positive authentication cache, so deleting an allowlist entry takes
effect on subsequent validations without a cache delay. Protected requests do
not depend on the Auth HTTP server being available. These are design properties,
not measured latency or availability results.

The cost is a shared trust boundary: each service holds the symmetric signing
secret and needs Redis access. A compromised service with signing and allowlist
write access can mint credentials. Restrict Redis permissions and network access,
distribute secrets through deployment configuration, and rotate the key across
services together. Rotation invalidates outstanding tokens. Roles in existing
tokens remain the issuance-time snapshot until expiry or revocation.

An authenticated Auth validation endpoint would keep signing keys out of the
other services, but adds an HTTP dependency and an extra hop on cache misses.
A short positive cache would delay revocation until its TTL (bounded by token
expiry). Such an endpoint also needs service authentication and strict timeouts.
It is not implemented here. Asymmetric signatures could later distribute only
public verification keys while retaining the Redis revocation check.

## Authorization and downstream calls

Service methods use `CurrentCaller` and the validated security context, never a
caller-supplied email as proof of identity.

| Operation | Policy |
| --- | --- |
| Read/list appointments | Patient matches `patient_email`; Doctor matches `doctor_email`. Lists query the caller's identity. |
| Book appointment | Patient can book only for their own email. |
| Update appointment | Caller owns or is assigned to the existing appointment; supported updates exclude ownership fields. |
| Images/uploads | Patient can query only their own email. Doctors read images through assigned appointments. |
| Read report | Caller owns or is assigned to its associated appointment. |
| Create/update report | Assigned Doctor only. |
| Predict | Doctor only, with appointment assignment checked before model calls. |
| `/auth/getUser`, `/auth/getRole` | Authenticated self-only; other emails return 403 before database access. |
| `/auth/getAllDoctors` | Authenticated booking directory with explicit booking fields only. |
| Change password | Uses the authenticated principal; body email cannot select another account. |

Report authorizes against the associated appointment in the shared MongoDB
collection, rather than trusting ownership fields in submitted reports.
Prediction forwards the original Bearer token to Appointment, Report, and both
ML routes. No unauthenticated internal endpoint or implicit service identity is
introduced. Receiving services must validate these credentials; the Python
service has its own JWT/Redis middleware because it cannot use the Java library.

## Configuration and account policy

Every Java service requires `app.jwt.secret: ${JWT_SECRET}`. The secret must be
canonical base64 encoding at least 32 cryptographically random bytes; missing,
malformed, or short values fail startup. No default secret is provided. Services
must share `REDIS_HOST`, `REDIS_PORT`, and optional `REDIS_PASSWORD`, configured
under `spring.data.redis`.

Only Auth sets `ckd.security.auth-service: true`. Login counts all attempts per
direct client IP using an atomic Redis INCR/EXPIRE script. Defaults are 5 attempts
per 60 seconds, configurable through `LOGIN_RATE_LIMIT` and
`LOGIN_RATE_WINDOW_SECONDS`. Excess requests return 429 with `Retry-After`;
unavailable rate-limit storage returns 503. The filter ignores arbitrary
`X-Forwarded-For` headers. Behind a proxy, securely establish the client address
or clients share the proxy's quota.

Registration and password changes require at least 12 Unicode code points and
at most 72 UTF-8 bytes to respect BCrypt's input limit. Existing passwords remain
usable for login. Public registration creates Patient accounts; submitted roles
cannot grant Doctor. Existing signup conflict responses still disclose an
already registered email; generic signup responses are a separate policy change.

## Build and verification

With Java 21 and Maven, from this repository root:

```sh
mvn '-Dtest=*SecurityTest,*OwnershipTest,*AuthorizationTest,AuthPolicyTest,*TokenForwardingTest,JwtTokenServiceTest,SecretStartupTest,ServiceClientsTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

These tests use generated signing keys and mocked Redis/repositories. MockMvc
exercises the actual filter chain for absent/expired/revoked tokens (401), wrong
roles and other-patient/unassigned-doctor access (403), and authorized access.
Library tests also cover rate limiting, Redis outages, token TTL/revocation, and
startup rejection of missing or short keys. Service tests cover password policy,
ownership, and forwarded tokens. Controlled model outputs are test fixtures.

For a full build, `mvn verify` runs the real-HTTP startup contracts and coverage
gates. Tests generate signing keys; no configured `JWT_SECRET` is needed. The
former empty context smoke classes have been removed. See [testing](../testing.md)
for Docker-backed flows and the coverage ratchet. Independently built service
repositories must first install or publish the library:

```sh
mvn -f shared-auth/pom.xml install
mvn -f services/auth-service/pom.xml verify
```

This checkout already contained the service security imports, policy checks,
and service tests. The library and this ADR were outside the repository; bringing
them into the reactor makes the implementation buildable from this checkout.
No claim is made that the existing service tests were originally written here
before those pre-existing changes.

## Operational management listeners

Actuator adds a separate loopback-only listener for health, metrics, and Prometheus.
Those three operational GET surfaces can be scraped without a patient JWT; all
other management paths are denied. The application-port catch-all JWT policy is
unchanged. This exception is bounded by the local listening port, not a caller's
Host/header value. See [observability](../observability.md) for ports and the local
Prometheus/Grafana profile; keep management ports off untrusted networks.
