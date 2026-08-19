package io.haifa.experiments.langgraph4j;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;

final class FixtureDefinitionCompiler {
    private static final Set<FixtureCapability> SUPPORTED = Set.copyOf(EnumSet.of(
            FixtureCapability.SEQUENCE,
            FixtureCapability.CONDITION,
            FixtureCapability.BOUNDED_LOOP,
            FixtureCapability.FIXED_ALL_OF,
            FixtureCapability.INTERRUPTION));

    void validate(FixtureDefinition definition) {
        definition.requiredCapabilities().stream()
                .filter(capability -> !SUPPORTED.contains(capability))
                .min(Comparator.comparing(Enum::name))
                .ifPresent(capability -> {
                    throw new UnsupportedFixtureCapabilityException(capability);
                });
    }
}
