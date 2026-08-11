package io.haifa.agent.spring.autoconfigure;

import io.haifa.agent.starter.HaifaAgentStarterBuilder;

/**
 * Ordered Spring extension point for the safe-default Starter builder.
 *
 * <p>Use this for trusted model catalogs and other Starter-level customization. Production
 * persistence remains an application-owned {@code HaifaAgent} bean rather than a second Starter.
 */
@FunctionalInterface
public interface HaifaAgentStarterCustomizer {
    /** Applies trusted host configuration before Java Tool beans are registered. */
    void customize(HaifaAgentStarterBuilder builder);
}
