# Local verification

| Claim | Evidence | Command | Local result |
|---|---|---|---|
| Independent callers share atomic quota | TokenBucketIT four-instance race | `./mvnw verify -Pintegration` | Passed |
| TTL/refill and NOSCRIPT recovery | TokenBucketIT real Redis | same | Passed |
| Tenant identity ignores forged header | TokenBucketIT authenticated HTTP | same | Passed |
| Outage policy and metrics explicit | TokenBucketTest and actual stopped-Redis demo | `./mvnw test`, README demo | Open degraded; closed 503 |

2026-09-14, Windows / Temurin 21.0.12+8 / Maven 3.9.16 / Docker Engine 29.6.1. `mvnw.cmd -B -ntp spotless:apply verify -Pintegration` passed two unit and four actual Redis/HTTP integration tests, zero skips. Independent source-only copy passed the same six tests.

Actual tests used Redis 8.10.1, four independent library instances and 128 concurrent-wave requests with refill-aware capacity accounting. SCRIPT FLUSH, expiry, scope separation, policy mismatch and forged tenant header checks passed.

The ordinary JAR was inspected: library class present, demo/application.properties absent. Packaged demo and Compose Redis started successfully. A healthy request returned four remaining tokens. After actually stopping Redis, catalog returned allowed/degraded/unknown balance; checkout returned 503. Metrics showed two degraded decisions. See `evidence/demo.json`. The owned demo JVM and Compose services were stopped afterward.

GitHub CI, Linux and Redis cluster/failover behavior remain unverified. Jedis logs a RESP2 auto-negotiation warning with this constructor; the actual protocol operations pass, and no RESP3 support is claimed. Source scan found no private-key or common access-token patterns; documented Basic-auth credentials are intentionally synthetic.
