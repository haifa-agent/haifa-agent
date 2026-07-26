package io.haifa.agent.sandbox.api;

import java.util.Objects;
import java.util.Set;

public record SandboxFilesystemPolicy(
        SandboxWorkspaceAccess workspaceAccess, boolean sensitivePathsDenied, Set<String> additionalPathPolicyRefs) {
    private static final int MAXIMUM_ADDITIONAL_PATH_POLICIES = 32;

    public SandboxFilesystemPolicy {
        workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess must not be null");
        additionalPathPolicyRefs = Set.copyOf(
                Objects.requireNonNull(additionalPathPolicyRefs, "additionalPathPolicyRefs must not be null"));
        if (additionalPathPolicyRefs.size() > MAXIMUM_ADDITIONAL_PATH_POLICIES) {
            throw new IllegalArgumentException("too many additional path policy references");
        }
        if (additionalPathPolicyRefs.stream().anyMatch(value -> !validReference(value))) {
            throw new IllegalArgumentException("additional path policy reference is invalid");
        }
    }

    public static SandboxFilesystemPolicy hostCompatible() {
        return new SandboxFilesystemPolicy(SandboxWorkspaceAccess.READ_WRITE, false, Set.of());
    }

    public boolean requiresIsolation() {
        return workspaceAccess == SandboxWorkspaceAccess.READ_ONLY
                || sensitivePathsDenied
                || !additionalPathPolicyRefs.isEmpty();
    }

    private static boolean validReference(String value) {
        return value != null && value.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    }
}
