package io.haifa.agent.model.api;

/** Performs one bounded provider health observation without mutating configuration. */
@FunctionalInterface
public interface ProviderHealthProbe {
    ProviderHealth check();
}
