# Limitations

Standalone trusted Redis; no cluster/failover tests. Server-clock jumps, Redis restart/data loss/eviction and deliberate fail-open decisions can relax enforcement. Backward time is clamped, forward refill capped at capacity. Quotas do not bound simultaneous downstream work.

Fresh connection per call trades connection churn for simple ownership. Per-command timeouts do not form a hard total deadline; DNS/OS scheduling may add delay. SCRIPT LOAD recovery is attempted once; ambiguous spends are not retried. Policy changes require coordinated rollout or idle expiry. Synthetic Basic-auth demo is not a production tenant identity system. Hashing IDs is not authorization or anonymization.
