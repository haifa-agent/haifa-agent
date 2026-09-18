package io.haifa.agent.skill.api;

import java.util.List;
import java.util.Objects;

public record SkillDiscoveryResult(List<SkillRegistration> registrations, List<SkillDiagnostic> diagnostics) {
    public SkillDiscoveryResult {
        registrations = List.copyOf(Objects.requireNonNull(registrations, "registrations must not be null"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }

    public static SkillDiscoveryResult empty() {
        return new SkillDiscoveryResult(List.of(), List.of());
    }
}
