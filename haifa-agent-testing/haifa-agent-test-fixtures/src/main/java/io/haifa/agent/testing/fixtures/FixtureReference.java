package io.haifa.agent.testing.fixtures;

import java.util.Objects;

/** Provider-neutral reference to one immutable fixture package version. */
public record FixtureReference(String id, int version) {
    public FixtureReference {
        id = Objects.requireNonNull(id, "fixture id must not be null").trim();
        if (!id.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("fixture id must be lowercase kebab-case");
        }
        if (version < 1) throw new IllegalArgumentException("fixture version must be positive");
    }
}
