package io.haifa.agent.sandbox.api;

public interface SandboxProvider {
    String providerId();

    SandboxCapabilities capabilities();

    default SandboxConfigurationDigest configurationDigest() {
        return SandboxConfigurationDigest.sha256Fields(
                java.util.List.of(providerId(), getClass().getName()));
    }

    default boolean supportsManagedProcess() {
        return false;
    }

    default SandboxPreflight preflight(SandboxProfile profile) {
        java.util.Objects.requireNonNull(profile, "profile must not be null");
        if (!providerId().equals(profile.providerId())) {
            throw new SandboxException("CAPABILITY_UNAVAILABLE", "sandbox provider binding does not match");
        }
        if (!configurationDigest().equals(profile.providerConfigurationDigest())) {
            throw new SandboxException("CAPABILITY_UNAVAILABLE", "sandbox provider configuration does not match");
        }
        if (!capabilities().satisfies(profile.requiredCapabilities())) {
            String code = profile.networkPolicy() == NetworkPolicy.DENY
                            && !capabilities().networkIsolation()
                    ? "NETWORK_POLICY_UNENFORCEABLE"
                    : "CAPABILITY_UNAVAILABLE";
            throw new SandboxException(code, "sandbox provider cannot satisfy the required capabilities");
        }
        return new SandboxPreflight(
                providerId(), providerId(), configurationDigest(), capabilities(), supportsManagedProcess());
    }

    SandboxSession open(SandboxProfile profile, WorkspaceMount mount);
}
