package tr.com.akifsen.limiter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TokenBucketTest {
    @Test
    void validatesPoliciesAndScope() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket.Policy(0, 1, TokenBucket.FailureMode.OPEN));
        assertThrows(
                IllegalArgumentException.class, () -> new TokenBucket.Policy(1, 10001, TokenBucket.FailureMode.CLOSED));
        assertThrows(IllegalArgumentException.class, () -> TokenBucket.key("tenant\nadmin", "checkout"));
        assertNotEquals(TokenBucket.key("a", "b"), TokenBucket.key("b", "a"));
        assertFalse(TokenBucket.key("private-tenant", "checkout").contains("private-tenant"));
    }

    @Test
    void unavailableRedisMakesFailureModeAndMetricsExplicit() {
        var bucket = new TokenBucket("127.0.0.1", 1);
        var open = bucket.acquire("a", "catalog", new TokenBucket.Policy(1, 1, TokenBucket.FailureMode.OPEN));
        var closed = bucket.acquire("a", "checkout", new TokenBucket.Policy(1, 1, TokenBucket.FailureMode.CLOSED));
        assertTrue(open.allowed());
        assertTrue(open.degraded());
        assertEquals(-1, open.remaining());
        assertFalse(closed.allowed());
        assertTrue(closed.degraded());
        assertEquals(2, bucket.metrics().get("degraded"));
    }
}
