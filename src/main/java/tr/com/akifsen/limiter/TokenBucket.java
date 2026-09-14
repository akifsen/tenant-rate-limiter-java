package tr.com.akifsen.limiter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisNoScriptException;

public final class TokenBucket {
    public enum FailureMode {
        OPEN,
        CLOSED
    }

    public record Policy(int capacity, int refillPerSecond, FailureMode failureMode) {
        public Policy {
            if (capacity < 1
                    || capacity > 10000
                    || refillPerSecond < 1
                    || refillPerSecond > 10000
                    || failureMode == null) throw new IllegalArgumentException("Invalid bucket policy");
        }
    }

    public record Decision(boolean allowed, long remaining, long retryAfterMillis, boolean degraded, String reason) {}

    private static final String SCRIPT = """
        local t=redis.call('TIME')
        local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
        local cap=tonumber(ARGV[1]); local rate=tonumber(ARGV[2])
        local old=redis.call('HMGET',KEYS[1],'tokens','at','cap','rate')
        if old[3] and (tonumber(old[3])~=cap or tonumber(old[4])~=rate) then
          return redis.error_reply('POLICY_MISMATCH')
        end
        local tokens=tonumber(old[1]) or cap
        local at=tonumber(old[2]) or now
        now=math.max(now,at)
        tokens=math.min(cap,tokens+(now-at)*rate/1000)
        local allowed=0;local retry=0
        if tokens>=1 then tokens=tokens-1;allowed=1 else retry=math.ceil((1-tokens)*1000/rate) end
        redis.call('HSET',KEYS[1],'tokens',tokens,'at',now,'cap',cap,'rate',rate)
        redis.call('PEXPIRE',KEYS[1],math.ceil(cap*1000/rate)+1000)
        return {allowed,math.floor(tokens),retry}
        """;
    private final String host, sha;
    private final int port;
    private final Semaphore admission = new Semaphore(32);
    private final LongAdder allowed = new LongAdder(), denied = new LongAdder(), degraded = new LongAdder();

    public TokenBucket(String host, int port) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) throw new IllegalArgumentException();
        this.host = host;
        this.port = port;
        try {
            sha = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-1").digest(SCRIPT.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String key(String tenant, String endpoint) {
        if (tenant == null
                || endpoint == null
                || !tenant.matches("[A-Za-z0-9_-]{1,64}")
                || !endpoint.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Use trusted bounded tenant and endpoint IDs");
        try {
            return "rate:v1:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest((tenant + "\n" + endpoint).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Decision acquire(String tenant, String endpoint, Policy policy) {
        Objects.requireNonNull(policy);
        String key = key(tenant, endpoint);
        if (!admission.tryAcquire()) return failure(policy, "LOCAL_CAPACITY");
        try (var redis = new Jedis(host, port, 250)) {
            Object result;
            try {
                result = redis.evalsha(
                        sha, List.of(key), List.of("" + policy.capacity(), "" + policy.refillPerSecond()));
            } catch (JedisNoScriptException e) {
                redis.scriptLoad(SCRIPT);
                result = redis.evalsha(
                        sha, List.of(key), List.of("" + policy.capacity(), "" + policy.refillPerSecond()));
            }
            var values = (List<?>) result;
            boolean pass = ((Number) values.get(0)).longValue() == 1;
            if (pass) allowed.increment();
            else denied.increment();
            return new Decision(
                    pass,
                    ((Number) values.get(1)).longValue(),
                    ((Number) values.get(2)).longValue(),
                    false,
                    pass ? "TOKEN" : "RATE_LIMITED");
        } catch (redis.clients.jedis.exceptions.JedisDataException e) {
            if (e.getMessage() != null && e.getMessage().contains("POLICY_MISMATCH")) {
                degraded.increment();
                denied.increment();
                return new Decision(false, -1, 1000, true, "POLICY_MISMATCH");
            }
            return failure(policy, "REDIS_ERROR");
        } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
            return failure(policy, "REDIS_UNAVAILABLE");
        } finally {
            admission.release();
        }
    }

    private Decision failure(Policy p, String reason) {
        degraded.increment();
        boolean pass = p.failureMode() == FailureMode.OPEN;
        if (pass) allowed.increment();
        else denied.increment();
        return new Decision(pass, -1, pass ? 0 : 1000, true, reason);
    }

    public Map<String, Long> metrics() {
        return Map.of("allowed", allowed.sum(), "denied", denied.sum(), "degraded", degraded.sum());
    }
}
