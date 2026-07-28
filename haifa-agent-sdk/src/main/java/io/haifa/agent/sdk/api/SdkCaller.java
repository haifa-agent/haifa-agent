package io.haifa.agent.sdk.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;

/** Trusted caller identity supplied by the host boundary, never by a command payload. */
public record SdkCaller(TenantRef tenant, PrincipalRef principal) {
    public SdkCaller {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
    }

    public static SdkCaller defaultPublicUser() {
        return new SdkCaller(new TenantRef("public"), new PrincipalRef("default-public-user", "user"));
    }
}
