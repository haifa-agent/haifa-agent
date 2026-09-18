package io.haifa.agent.skill.api;

import java.time.Instant;
import java.util.Objects;

public record SkillActivation(
        FrozenSkillBinding binding,
        String reason,
        String requestedBy,
        Instant activatedAt,
        int instructionBytes,
        int estimatedTokens) {
    public SkillActivation {
        binding = Objects.requireNonNull(binding, "binding must not be null");
        reason = SkillValues.text(reason, "reason", 512);
        requestedBy = SkillValues.text(requestedBy, "requestedBy", 128);
        activatedAt = Objects.requireNonNull(activatedAt, "activatedAt must not be null");
        if (instructionBytes < 1) throw new IllegalArgumentException("instructionBytes must be positive");
        if (estimatedTokens < 1) throw new IllegalArgumentException("estimatedTokens must be positive");
    }
}
