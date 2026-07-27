package io.haifa.agent.sandbox.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.execution.api.SandboxProfileRef;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SandboxProfileTest {
    private static final SandboxConfigurationDigest CONFIGURATION =
            SandboxConfigurationDigest.sha256Fields(List.of("local-native", "test-adapter", "1"));

    @Test
    void contentDigestIsStableAcrossSetIterationOrder() {
        SandboxProfile first = profile(
                Set.of("java", "git"), new LinkedHashSet<>(List.of("PATH", "JAVA_HOME")), Set.of("cache", "tools"));
        SandboxProfile second = profile(
                new LinkedHashSet<>(List.of("git", "java")),
                new LinkedHashSet<>(List.of("JAVA_HOME", "PATH")),
                new LinkedHashSet<>(List.of("tools", "cache")));

        assertThat(first).isEqualTo(second);
        assertThat(first.contentDigest()).isEqualTo(second.contentDigest());
    }

    @Test
    void rejectsRequirementsThatCannotExpressTheRequestedPolicy() {
        assertThatThrownBy(() -> new SandboxProfile(
                        new SandboxProfileRef("network-deny", "1"),
                        "local-native",
                        CONFIGURATION,
                        Set.of(),
                        Set.of(),
                        true,
                        NetworkPolicy.DENY,
                        SandboxFilesystemPolicy.hostCompatible(),
                        new SandboxCapabilities(true, false, false, false, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("network isolation");

        assertThatThrownBy(() -> new SandboxProfile(
                        new SandboxProfileRef("sensitive", "1"),
                        "local-native",
                        CONFIGURATION,
                        Set.of(),
                        Set.of(),
                        true,
                        NetworkPolicy.ALLOW,
                        new SandboxFilesystemPolicy(SandboxWorkspaceAccess.READ_WRITE, true, Set.of()),
                        new SandboxCapabilities(true, false, false, false, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filesystem isolation");
    }

    @Test
    void rejectsHostPathsAsAdditionalPolicyReferences() {
        assertThatThrownBy(() -> new SandboxFilesystemPolicy(
                        SandboxWorkspaceAccess.READ_WRITE, true, Set.of("C:\\Users\\owner\\.m2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SandboxProfile profile(
            Set<String> executables, Set<String> environmentNames, Set<String> pathPolicies) {
        return new SandboxProfile(
                new SandboxProfileRef("local-native-default", "1"),
                "local-native",
                CONFIGURATION,
                executables,
                environmentNames,
                true,
                NetworkPolicy.DENY,
                new SandboxFilesystemPolicy(SandboxWorkspaceAccess.READ_WRITE, true, pathPolicies),
                new SandboxCapabilities(true, true, true, false, false));
    }
}
