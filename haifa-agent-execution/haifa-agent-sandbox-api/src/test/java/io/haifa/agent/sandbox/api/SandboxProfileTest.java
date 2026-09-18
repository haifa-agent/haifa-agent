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
            SandboxConfigurationDigest.sha256Fields(List.of("host-guarded", "test-adapter", "1"));

    @Test
    void contentDigestIsStableAcrossSetIterationOrder() {
        SandboxProfile first = profile(Set.of("java", "git"), new LinkedHashSet<>(List.of("PATH", "JAVA_HOME")));
        SandboxProfile second =
                profile(new LinkedHashSet<>(List.of("git", "java")), new LinkedHashSet<>(List.of("JAVA_HOME", "PATH")));

        assertThat(first).isEqualTo(second);
        assertThat(first.contentDigest()).isEqualTo(second.contentDigest());
    }

    @Test
    void rejectsInvalidEnvironmentNames() {
        assertThatThrownBy(() -> profile(Set.of("git"), Set.of("not a name")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedEnvironmentNames");
    }

    @Test
    void hostGuardedFactoryBindsTheHostProvider() {
        SandboxProfile hostProfile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("cli-host-guarded", "1"), CONFIGURATION, Set.of("git"), Set.of("PATH"), true);

        assertThat(hostProfile.providerId()).isEqualTo("host-guarded");
        assertThat(hostProfile.shellAllowed()).isTrue();
    }

    private static SandboxProfile profile(Set<String> executables, Set<String> environmentNames) {
        return new SandboxProfile(
                new SandboxProfileRef("host-guarded-default", "1"),
                "host-guarded",
                CONFIGURATION,
                executables,
                environmentNames,
                true);
    }
}
