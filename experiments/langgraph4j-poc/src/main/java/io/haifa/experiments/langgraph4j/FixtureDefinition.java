package io.haifa.experiments.langgraph4j;

import java.util.Objects;
import java.util.Set;

record FixtureDefinition(String id, Set<FixtureCapability> requiredCapabilities) {
    FixtureDefinition {
        id = Objects.requireNonNull(id, "id must not be null");
        requiredCapabilities = Set.copyOf(Objects.requireNonNull(
                requiredCapabilities, "requiredCapabilities must not be null"));
    }
}
