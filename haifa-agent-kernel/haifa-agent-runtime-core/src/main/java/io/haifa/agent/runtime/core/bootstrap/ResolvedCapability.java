package io.haifa.agent.runtime.core.bootstrap;

import java.util.Objects;
import java.util.Optional;

public record ResolvedCapability(
        String capabilityId, String version, String bindingRef, String configurationDigest, boolean authorized) {
    public ResolvedCapability {
        capabilityId = requireText(capabilityId, "capabilityId");
        version = requireText(version, "version");
        if (bindingRef != null) {
            bindingRef = requireText(bindingRef, "bindingRef");
            if (looksLikeHostLocation(bindingRef)) {
                throw new IllegalArgumentException("bindingRef must be opaque and must not contain a host location");
            }
        }
        configurationDigest = requireText(configurationDigest, "configurationDigest");
    }

    public Optional<String> optionalBindingRef() {
        return Optional.ofNullable(bindingRef);
    }

    private static boolean looksLikeHostLocation(String value) {
        return value.startsWith("/")
                || value.startsWith("\\")
                || value.matches("^[A-Za-z]:.*")
                || value.contains("://");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    @Override
    public String toString() {
        return "ResolvedCapability[capabilityId=" + capabilityId + ", version=" + version + ", authorized=" + authorized
                + "]";
    }
}
