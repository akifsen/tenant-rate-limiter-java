# Tenant Rate Limiter Java

Repository: https://github.com/akifsen/tenant-rate-limiter-java. Local verification results are documented below; hosted CI status must be checked in GitHub Actions.

A small Redis token bucket library and authenticated Spring Boot demo. Tenant and endpoint IDs identify each bucket; one bounded Lua script reads Redis TIME, refills and spends atomically. Multiple application instances share the same server-side balance.

## Build and demo

Java 21, Maven 3.9.16, Boot 4.1.1, Jedis 8.0.1 (stable authx-core 0.2.0 override), Redis 8.10.1, Testcontainers 2.0.5. No prerelease dependencies are intentionally selected.

```powershell
.\mvnw.cmd -B verify -Pintegration
docker compose up -d --wait
java -jar target/tenant-rate-limiter-java-0.1.0-SNAPSHOT-demo.jar
# From another terminal, repeat six times quickly:
curl.exe -u tenant-a:synthetic-local-only http://127.0.0.1:18083/api/checkout
docker compose down
```

The demo binds loopback 18083, Redis 16380. `--demo.redis.port=...` overrides Redis port. Synthetic Basic-auth accounts tenant-a and tenant-b are local examples only. `/api/checkout` fails closed on Redis errors; `/api/catalog` fails open with `degraded=true`. Both allow five tokens with refill one/second. Unknown endpoints return 404, missing authentication 401, exhausted quota 429, fail-closed infrastructure errors 503. Denials carry Retry-After in rounded-up seconds; JSON retains milliseconds. `/demo/metrics` requires authentication and exposes fixed-cardinality allowed/denied/degraded counters.

The ordinary JAR excludes the example and application.properties; the executable `-demo.jar` contains them. The library package is `tr.com.akifsen.limiter`:

```java
var limiter = new TokenBucket("127.0.0.1", 16380);
var policy = new TokenBucket.Policy(20, 5, TokenBucket.FailureMode.CLOSED);
var decision = limiter.acquire(trustedTenant, "checkout", policy);
```

Callers implement `TrustedTenantResolver` against their authentication context. The demo explicitly maps authenticated principals; it never trusts X-Tenant. Tenant/endpoint IDs are bounded ASCII identifiers, hashed together with a separator for Redis keys. Hashing is not anonymization or authorization; only the resolver establishes authority.

## Semantics

Decisions expose allowed, remaining whole tokens, retryAfterMillis, degraded and a bounded reason. Healthy allowance consumes one token. Denial retains fractional refill progress. Degraded balance is unknown (`remaining=-1`); fail-open allowance does not pretend a Redis token was spent. Local admission saturation follows the endpoint's same explicit failure mode. Metrics are per library instance, not a global quota tally.

Redis TIME provides milliseconds for every caller, avoiding application-clock disagreement. Time moving backward is clamped to the stored bucket timestamp, delaying refill; forward jumps can refill up to capacity. This is wall-clock behavior, not a monotonic-clock guarantee. Redis failover/rollback, eviction, manual deletion or restart without persisted keys can reset quota. The demo uses noeviction; there is no cluster or durable-global-limit claim.

Policies allow capacity/refill 1..10,000. One Redis key is touched, with a constant number of operations and no loops. Idle keys expire after a full refill interval plus one second. Ongoing requests renew TTL. Policy capacity/rate mismatch on an existing key always fails closed with POLICY_MISMATCH, including OPEN endpoints; coordinate rollout or wait for idle expiry. Failure mode itself may differ by endpoint owner.

EVALSHA recovers once from NOSCRIPT using SCRIPT LOAD then EVALSHA (at most three commands, no retry loop). A second flush or ambiguous network failure returns an explicit degraded decision; it never blindly retries token consumption. Per instance: 32 admitted operations, no waiting queue, a fresh connection per call with 250ms connect/read timeout. Multiple commands mean this is not a 250ms total deadline; DNS and OS scheduling can add delay. For this local lab use a numeric host. No connection pool or hidden unlimited worker queue is introduced.

**A rate limit is not a concurrency bound.** Slow accepted requests can overlap beyond the token capacity; downstream concurrency needs separate admission. Fail-open deliberately trades quota enforcement for availability. Production integration needs credentialed Redis/TLS, shared policy rollout and trusted tenancy beyond this local demo.

## Evidence

`./mvnw verify -Pintegration` runs actual Redis races across four library instances, scope isolation/refill, SCRIPT FLUSH recovery, idle expiration, policy mismatch, authenticated HTTP and forged-header rejection. Unavailable Redis tests verify both failure policies and counters. Docker absence fails the integration build. [Verification](docs/verification.md) records executed results; [ADR](docs/adr/001-token-bucket.md) records tradeoffs. No production SLA, published release or GitHub CI pass is claimed. MIT source; upstream dependency licenses remain applicable.

## Review corrections — 2026-09-20

Demo-only MVC and Spring Security dependencies are optional in the published POM; the regular library does not force a web/security stack into consumers.
