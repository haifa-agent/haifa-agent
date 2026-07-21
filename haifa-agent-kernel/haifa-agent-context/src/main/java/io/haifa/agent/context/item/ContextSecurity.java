package io.haifa.agent.context.item;

import java.util.Objects;
import java.util.Set;

public record ContextSecurity(Set<String> labels, boolean providerDisclosureAllowed) {
    public static final ContextSecurity INTERNAL = new ContextSecurity(Set.of("internal"), true);

    public ContextSecurity {
        labels = Set.copyOf(Objects.requireNonNull(labels, "labels must not be null"));
    }
}
