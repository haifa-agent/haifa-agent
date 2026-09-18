package io.haifa.example.runtime.scenario;

import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.tool.core.DefaultToolCatalog;
import java.util.Optional;
import java.util.Set;

/**
 * One explicit Runtime Core capability scenario used by the non-published demo application.
 *
 * <p>This example contract is not an SDK API and must not be used as an application dependency.
 */
public interface RuntimeScenario extends AutoCloseable {
    String id();

    String defaultObjective();

    String instructions();

    default int maxOutputTokens() {
        return 256;
    }

    default Set<String> allowedToolAliases() {
        return Set.of();
    }

    default Set<String> allowedSkillAliases() {
        return Set.of();
    }

    default Optional<DefaultToolCatalog> toolCatalog() {
        return Optional.empty();
    }

    default void configure(RuntimeCoreBuilder builder) {}

    default boolean usesCapabilityCalls() {
        return !allowedToolAliases().isEmpty();
    }

    @Override
    default void close() {}
}
