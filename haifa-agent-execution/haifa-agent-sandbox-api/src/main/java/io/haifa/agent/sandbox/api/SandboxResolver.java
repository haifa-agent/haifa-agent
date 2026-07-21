package io.haifa.agent.sandbox.api;

import io.haifa.agent.execution.api.SandboxProfileRef;

@FunctionalInterface
public interface SandboxResolver {
    SandboxProfile resolve(SandboxProfileRef reference);
}
