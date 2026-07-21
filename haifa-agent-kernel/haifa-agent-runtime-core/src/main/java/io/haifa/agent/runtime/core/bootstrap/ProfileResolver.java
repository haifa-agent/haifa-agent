package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.runtime.api.RuntimeOverrides;

@FunctionalInterface
public interface ProfileResolver {
    ResolvedProfile resolve(String profileId, RuntimeOverrides overrides);
}
