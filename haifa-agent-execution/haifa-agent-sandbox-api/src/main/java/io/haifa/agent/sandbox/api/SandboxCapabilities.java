package io.haifa.agent.sandbox.api;

public record SandboxCapabilities(
        boolean processTreeTermination,
        boolean filesystemMountIsolation,
        boolean networkIsolation,
        boolean cpuLimit,
        boolean memoryLimit) {}
