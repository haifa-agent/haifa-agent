package io.haifa.agent.sandbox.api;

public interface SandboxProvider {
    String providerId();

    SandboxCapabilities capabilities();

    SandboxSession open(SandboxProfile profile, WorkspaceMount mount);
}
