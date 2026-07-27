package io.haifa.agent.sandbox.api;

public record SandboxCapabilities(
        boolean processTreeTermination,
        boolean filesystemMountIsolation,
        boolean networkIsolation,
        boolean cpuLimit,
        boolean memoryLimit) {
    public boolean satisfies(SandboxCapabilities required) {
        return (!required.processTreeTermination || processTreeTermination)
                && (!required.filesystemMountIsolation || filesystemMountIsolation)
                && (!required.networkIsolation || networkIsolation)
                && (!required.cpuLimit || cpuLimit)
                && (!required.memoryLimit || memoryLimit);
    }
}
