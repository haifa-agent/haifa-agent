package io.haifa.agent.testing.sdk;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic Clock, ID and Caller boundaries for SDK tests. */
public final class FixedSdkBoundaries {
    private FixedSdkBoundaries() {}

    public static TimeProvider time(Instant value) {
        Instant fixed = Objects.requireNonNull(value, "value must not be null");
        return () -> fixed;
    }

    public static IdentifierGenerator identifiers(String prefix) {
        String checked = text(prefix, "prefix");
        AtomicLong sequence = new AtomicLong();
        return () -> checked + "-" + sequence.incrementAndGet();
    }

    public static SdkCallerProvider caller(String tenantId, String principalId) {
        SdkCaller fixed = new SdkCaller(
                new TenantRef(text(tenantId, "tenantId")), new PrincipalRef(text(principalId, "principalId"), "user"));
        return () -> fixed;
    }

    private static String text(String value, String field) {
        String checked =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return checked;
    }
}
