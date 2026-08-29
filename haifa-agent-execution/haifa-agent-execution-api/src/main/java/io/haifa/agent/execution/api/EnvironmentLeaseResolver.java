package io.haifa.agent.execution.api;

@FunctionalInterface
public interface EnvironmentLeaseResolver {
    ResolvedExecutionEnvironment resolve(ExecutionEnvironmentRef reference);
}
