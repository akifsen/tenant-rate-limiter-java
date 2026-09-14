package tr.com.akifsen.limiter;

import java.security.Principal;

@FunctionalInterface
public interface TrustedTenantResolver {
    /** Resolve only after authentication; implementations own authorization and tenant mapping. */
    String resolve(Principal authenticatedPrincipal);
}
