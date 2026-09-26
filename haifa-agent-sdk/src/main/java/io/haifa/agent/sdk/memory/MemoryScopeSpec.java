package io.haifa.agent.sdk.memory;

import io.haifa.agent.memory.api.MemoryScopeType;
import java.util.Objects;
import java.util.Optional;

/**
 * Caller-controlled bucket choice. It carries no tenant or owner: those always come from the trusted SDK caller,
 * so a caller cannot address another principal's memories.
 */
public record MemoryScopeSpec(MemoryScopeType type, Optional<String> targetId) {
    public MemoryScopeSpec {
        type = Objects.requireNonNull(type, "type must not be null");
        targetId = Objects.requireNonNull(targetId, "targetId must not be null").map(value -> requireText(value, 256));
        if (type == MemoryScopeType.USER && targetId.isPresent()) {
            throw new IllegalArgumentException("USER scope target is resolved from trusted caller");
        }
        if (type != MemoryScopeType.USER && targetId.isEmpty()) {
            throw new IllegalArgumentException("AGENT and SESSION scopes require a logical target");
        }
    }

    public static MemoryScopeSpec user() {
        return new MemoryScopeSpec(MemoryScopeType.USER, Optional.empty());
    }

    /** Memory shared by every conversation of one Agent Definition for the calling user. */
    public static MemoryScopeSpec agent(String agentDefinitionId) {
        return new MemoryScopeSpec(MemoryScopeType.AGENT, Optional.of(agentDefinitionId));
    }

    public static MemoryScopeSpec session(String sessionId) {
        return new MemoryScopeSpec(MemoryScopeType.SESSION, Optional.of(sessionId));
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
