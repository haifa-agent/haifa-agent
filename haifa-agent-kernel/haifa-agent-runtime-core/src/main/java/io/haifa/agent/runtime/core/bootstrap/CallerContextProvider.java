package io.haifa.agent.runtime.core.bootstrap;

@FunctionalInterface
public interface CallerContextProvider {
    RuntimeCallerContext current();
}
