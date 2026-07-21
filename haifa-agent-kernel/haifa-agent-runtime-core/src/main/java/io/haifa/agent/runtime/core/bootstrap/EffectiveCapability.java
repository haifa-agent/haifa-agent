package io.haifa.agent.runtime.core.bootstrap;

import java.util.Objects;
import java.util.Optional;

public record EffectiveCapability(String capabilityId, String version, String bindingRef, String configurationDigest)
        implements Comparable<EffectiveCapability> {
    public EffectiveCapability {
        capabilityId = requireText(capabilityId, "capabilityId");
        version = requireText(version, "version");
        bindingRef = bindingRef == null ? null : requireText(bindingRef, "bindingRef");
        configurationDigest = requireText(configurationDigest, "configurationDigest");
    }

    public Optional<String> optionalBindingRef() {
        return Optional.ofNullable(bindingRef);
    }

    @Override
    public int compareTo(EffectiveCapability other) {
        return capabilityId.compareTo(other.capabilityId);
    }

    @Override
    public String toString() {
        return "EffectiveCapability[capabilityId=" + capabilityId + ", version=" + version + "]";
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
