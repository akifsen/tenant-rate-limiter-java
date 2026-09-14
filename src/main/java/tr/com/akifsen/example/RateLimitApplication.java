package tr.com.akifsen.example;

import java.security.Principal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import tr.com.akifsen.limiter.*;

@SpringBootApplication
public class RateLimitApplication {
    public static void main(String[] args) {
        SpringApplication.run(RateLimitApplication.class, args);
    }

    @Bean
    TokenBucket bucket(@Value("${demo.redis.port:16380}") int port) {
        return new TokenBucket("127.0.0.1", port);
    }

    @Bean
    TrustedTenantResolver tenants() {
        return principal -> {
            if (principal == null || !java.util.Set.of("tenant-a", "tenant-b").contains(principal.getName()))
                throw new IllegalArgumentException("Unknown authenticated tenant");
            return principal.getName();
        };
    }

    @Bean
    UserDetailsService users() {
        return new InMemoryUserDetailsManager(
                User.withUsername("tenant-a")
                        .password("{noop}synthetic-local-only")
                        .roles("TENANT")
                        .build(),
                User.withUsername("tenant-b")
                        .password("{noop}synthetic-local-only")
                        .roles("TENANT")
                        .build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @RestController
    static class Controller {
        private final TokenBucket bucket;
        private final TrustedTenantResolver resolver;

        Controller(TokenBucket bucket, TrustedTenantResolver resolver) {
            this.bucket = bucket;
            this.resolver = resolver;
        }

        @GetMapping("/api/{endpoint}")
        ResponseEntity<TokenBucket.Decision> request(@PathVariable String endpoint, Principal principal) {
            if (!endpoint.equals("catalog") && !endpoint.equals("checkout"))
                return ResponseEntity.notFound().build();
            var policy = new TokenBucket.Policy(
                    5, 1, endpoint.equals("catalog") ? TokenBucket.FailureMode.OPEN : TokenBucket.FailureMode.CLOSED);
            var result = bucket.acquire(resolver.resolve(principal), endpoint, policy);
            var response = ResponseEntity.status(result.allowed() ? 200 : result.degraded() ? 503 : 429)
                    .header("X-RateLimit-Remaining", "" + result.remaining())
                    .header("X-RateLimit-Degraded", "" + result.degraded());
            if (!result.allowed())
                response.header("Retry-After", "" + Math.max(1, (result.retryAfterMillis() + 999) / 1000));
            return response.body(result);
        }

        @GetMapping("/demo/metrics")
        Map<String, Long> metrics() {
            return bucket.metrics();
        }
    }
}
