package io.haifa.agent.execution.api;

import java.util.Map;

@FunctionalInterface
public interface EnvironmentLeaseResolver {
    Map<String, String> resolve(ExecutionEnvironmentRef reference);
}
