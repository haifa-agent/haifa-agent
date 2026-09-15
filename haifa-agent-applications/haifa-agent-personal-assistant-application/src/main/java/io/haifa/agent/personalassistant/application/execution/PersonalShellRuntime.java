package io.haifa.agent.personalassistant.application.execution;

import java.util.Objects;
import java.util.Set;

/** Product-owned host command/script runtime identity used by the deterministic acceptance model. */
public record PersonalShellRuntime(String operatingSystem, Set<String> scriptLanguages) {
    public PersonalShellRuntime {
        operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem must not be null")
                .trim();
        if (operatingSystem.isEmpty()) throw new IllegalArgumentException("operatingSystem must not be blank");
        scriptLanguages = Set.copyOf(Objects.requireNonNull(scriptLanguages, "scriptLanguages must not be null"));
        if (scriptLanguages.isEmpty()) throw new IllegalArgumentException("scriptLanguages must not be empty");
    }
}
