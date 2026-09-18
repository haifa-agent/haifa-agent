package io.haifa.agent.transport.http;

import java.util.Objects;

/** Authenticated identity supplied by the host; never decoded from a request body. */
public record TrustedCallerContext(String tenantId, String principalType, String principalId, String productId) {
    public TrustedCallerContext {
        tenantId = text(tenantId, "tenantId");
        principalType = text(principalType, "principalType");
        principalId = text(principalId, "principalId");
        productId = text(productId, "productId");
    }

    private static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new IllegalArgumentException(field + " must contain 1..256 characters");
        }
        return normalized;
    }
}
