package io.haifa.agent.sdk.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;
import java.util.Set;

/** Trusted caller identity supplied by the host boundary, never by a command payload. */
public record SdkCaller(TenantRef tenant, PrincipalRef principal, Set<String> permissions) {
    public SdkCaller {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions must not be null"));
    }

    public SdkCaller(TenantRef tenant, PrincipalRef principal) {
        this(tenant, principal, Set.of());
    }

    public static SdkCaller defaultPublicUser() {
        return new SdkCaller(
                new TenantRef("public"),
                new PrincipalRef("default-public-user", "user"),
                Set.of("memory:read", "memory:propose"));
    }
}
