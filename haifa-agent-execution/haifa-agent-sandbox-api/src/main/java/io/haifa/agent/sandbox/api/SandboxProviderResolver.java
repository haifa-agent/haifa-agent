package io.haifa.agent.sandbox.api;

@FunctionalInterface
public interface SandboxProviderResolver {
    SandboxProvider resolve(SandboxProfile profile);
}
