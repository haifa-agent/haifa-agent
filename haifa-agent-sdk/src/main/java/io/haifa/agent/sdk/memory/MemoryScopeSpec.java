package io.haifa.agent.sdk.memory;

import io.haifa.agent.memory.api.MemoryScopeType;
import io.haifa.agent.memory.api.MemorySecurityLabel;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Caller-controlled scope shape; trusted Tenant and Principal are supplied by the SDK host context. */
public record MemoryScopeSpec(
        MemoryScopeType type, Optional<String> targetId, Set<MemorySecurityLabel> securityLabels) {
    public MemoryScopeSpec {
        type = Objects.requireNonNull(type, "type must not be null");
        targetId = Objects.requireNonNull(targetId, "targetId must not be null").map(value -> requireText(value, 256));
        securityLabels = Set.copyOf(Objects.requireNonNull(securityLabels, "securityLabels must not be null"));
        if (type == MemoryScopeType.USER && targetId.isPresent()) {
            throw new IllegalArgumentException("USER scope target is resolved from trusted caller");
        }
        if (type != MemoryScopeType.USER && targetId.isEmpty()) {
            throw new IllegalArgumentException("RUN and SESSION scopes require a logical target");
        }
    }

    public static MemoryScopeSpec user() {
        return new MemoryScopeSpec(MemoryScopeType.USER, Optional.empty(), Set.of());
    }

    public static MemoryScopeSpec session(String sessionId) {
        return new MemoryScopeSpec(MemoryScopeType.SESSION, Optional.of(sessionId), Set.of());
    }

    public static MemoryScopeSpec run(String runId) {
        return new MemoryScopeSpec(MemoryScopeType.RUN, Optional.of(runId), Set.of());
    }

    static String requireText(String value, int maximumLength) {
        String normalized =
                Objects.requireNonNull(value, "value must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException("value length is invalid");
        }
        return normalized;
    }
}
