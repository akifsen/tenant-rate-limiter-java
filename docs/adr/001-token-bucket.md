# ADR 001: Redis time and atomic single-key admission

Accepted. Use Redis TIME within Lua so application clocks cannot independently mint tokens. One key contains fractional tokens, last timestamp and policy capacity/rate; atomic script execution resolves concurrent spends. Reject policy disagreement instead of silently resetting capacity during mixed-version rollout.

The library returns degraded metadata instead of hiding infrastructure failure behind a boolean. Fail-open and fail-closed are explicit endpoint policies; invalid policy never opens access. NOSCRIPT reload is bounded and network failures do not retry an ambiguous spend. A fresh short-lived connection simplifies ownership in this lab at the cost of connection churn. Distributed concurrency control and cluster failover guarantees are outside scope.
