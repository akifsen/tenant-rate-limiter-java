package tr.com.akifsen.limiter;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.testcontainers.containers.GenericContainer;
import redis.clients.jedis.Jedis;
import tr.com.akifsen.example.RateLimitApplication;

@Timeout(25)
class TokenBucketIT {
    static GenericContainer<?> redis;
    TokenBucket bucket;

    @BeforeAll
    static void start() {
        redis = new GenericContainer<>("redis:8.10.1-alpine").withExposedPorts(6379);
        redis.start();
    }

    @AfterAll
    static void stop() {
        if (redis != null) redis.stop();
    }

    @BeforeEach
    void setup() {
        bucket = new TokenBucket(redis.getHost(), redis.getMappedPort(6379));
        try (var j = connection()) {
            j.flushDB();
        }
    }

    Jedis connection() {
        return new Jedis(redis.getHost(), redis.getMappedPort(6379), 1000);
    }

    @Test
    void concurrentIndependentInstancesCannotSpendSameTokens() throws Exception {
        var instances = new ArrayList<TokenBucket>();
        for (int i = 0; i < 4; i++) instances.add(new TokenBucket(redis.getHost(), redis.getMappedPort(6379)));
        var barrier = new CyclicBarrier(16);
        var policy = new TokenBucket.Policy(25, 1, TokenBucket.FailureMode.CLOSED);
        try (var pool = Executors.newFixedThreadPool(16)) {
            var futures = new ArrayList<Future<List<TokenBucket.Decision>>>();
            long start = System.nanoTime();
            for (int i = 0; i < 16; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    barrier.await();
                    var results = new ArrayList<TokenBucket.Decision>();
                    for (int n = 0; n < 8; n++)
                        results.add(instances.get(index % 4).acquire("same", "checkout", policy));
                    return results;
                }));
            }
            var decisions = new ArrayList<TokenBucket.Decision>();
            for (var f : futures) decisions.addAll(f.get(10, TimeUnit.SECONDS));
            long successes =
                    decisions.stream().filter(TokenBucket.Decision::allowed).count();
            assertTrue(successes >= 25);
            assertTrue(successes <= 25 + (System.nanoTime() - start) / 1_000_000_000L);
            assertTrue(decisions.stream().noneMatch(TokenBucket.Decision::degraded));
            assertEquals(128, decisions.size());
        }
    }

    @Test
    void tenantEndpointRefillAndScriptReload() throws Exception {
        var p = new TokenBucket.Policy(1, 1, TokenBucket.FailureMode.CLOSED);
        assertTrue(bucket.acquire("a", "one", p).allowed());
        assertFalse(bucket.acquire("a", "one", p).allowed());
        assertTrue(bucket.acquire("b", "one", p).allowed());
        assertTrue(bucket.acquire("a", "two", p).allowed());
        try (var j = connection()) {
            j.scriptFlush();
        }
        assertTrue(bucket.acquire("c", "one", p).allowed());
        long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        TokenBucket.Decision result;
        do {
            Thread.sleep(25);
            result = bucket.acquire("a", "one", p);
        } while (!result.allowed() && System.nanoTime() < end);
        assertTrue(result.allowed());
        assertFalse(result.degraded());
    }

    @Test
    void idleKeyExpiresAndConflictingPolicyFailsClosed() throws Exception {
        var p = new TokenBucket.Policy(1, 1000, TokenBucket.FailureMode.OPEN);
        bucket.acquire("a", "one", p);
        var mismatch = bucket.acquire("a", "one", new TokenBucket.Policy(2, 1000, TokenBucket.FailureMode.OPEN));
        assertFalse(mismatch.allowed());
        assertEquals("POLICY_MISMATCH", mismatch.reason());
        try (var j = connection()) {
            String key = TokenBucket.key("a", "one");
            long ttl = j.pttl(key);
            assertTrue(ttl > 0 && ttl <= 1001);
            long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (j.exists(key) && System.nanoTime() < end) Thread.sleep(25);
            assertFalse(j.exists(key));
        }
    }

    @Test
    void authenticatedHttpIgnoresForgedTenantHeaderAndReturns429() throws Exception {
        var app = new SpringApplication(RateLimitApplication.class);
        try (var context = app.run("--server.port=0", "--demo.redis.port=" + redis.getMappedPort(6379));
                var client = HttpClient.newHttpClient()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/checkout");
            assertEquals(
                    401,
                    client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
                            .statusCode());
            String auth = "Basic "
                    + Base64.getEncoder()
                            .encodeToString(
                                    "tenant-a:synthetic-local-only".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            HttpResponse<String> response = null;
            for (int i = 0; i < 6; i++)
                response = client.send(
                        HttpRequest.newBuilder(uri)
                                .header("Authorization", auth)
                                .header("X-Tenant", "forged-" + i)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
            assertEquals(429, response.statusCode());
            assertTrue(response.headers().firstValue("Retry-After").isPresent());
            try (var j = connection()) {
                assertTrue(j.exists(TokenBucket.key("tenant-a", "checkout")));
                assertFalse(j.exists(TokenBucket.key("forged-5", "checkout")));
            }
        }
    }
}
