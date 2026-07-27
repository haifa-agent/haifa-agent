package io.haifa.agent.sandbox.localnative;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxCapabilities;
import io.haifa.agent.sandbox.api.SandboxFilesystemPolicy;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxWorkspaceAccess;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalNativeAdapterPlanTest {
    @TempDir
    Path temporary;

    @Test
    void seatbeltPolicyKeepsCommandOutOfPolicyAndOnlyAllowsRequestedNetwork() {
        LocalNativeSandboxConfiguration configuration = configuration();
        Path workspace = temporary.resolve("workspace");
        Path controls = temporary.resolve("controls");
        Path sensitive = temporary.resolve("sensitive");
        String denied = MacSeatbeltAdapter.policy(
                configuration,
                profile(NetworkPolicy.DENY, SandboxWorkspaceAccess.READ_WRITE),
                workspace,
                controls,
                List.of());
        String allowed = MacSeatbeltAdapter.policy(
                configuration,
                profile(NetworkPolicy.ALLOW, SandboxWorkspaceAccess.READ_ONLY),
                workspace,
                controls,
                List.of());

        assertThat(denied)
                .contains("(deny default)")
                .contains(escape(workspace.toString()))
                .contains(escape(controls.toString()))
                .contains(escape(sensitive.toString()))
                .doesNotContain("(allow network*)")
                .doesNotContain("model-provided-command");
        assertThat(allowed)
                .contains("(allow network*)")
                .contains("(allow file-read*")
                .doesNotContain("(allow file-write* (subpath \"" + escape(workspace.toString()) + "\"))");
    }

    @Test
    void bubblewrapUsesStructuredArgumentsAndDoesNotLetCommandChangeMounts() {
        LocalNativeSandboxConfiguration configuration = configuration();
        Path workspace = temporary.resolve("workspace");
        ExecutionCommand command =
                ExecutionCommand.direct(List.of("tool", "--bind", "/host", "/", "value with spaces"));
        List<String> denied = new LinuxBubblewrapAdapter()
                .prepare(
                        configuration,
                        profile(NetworkPolicy.DENY, SandboxWorkspaceAccess.READ_WRITE),
                        workspace,
                        workspace.resolve("subdir"),
                        temporary.resolve("controls"),
                        List.of(),
                        command)
                .argv();
        List<String> allowed = new LinuxBubblewrapAdapter()
                .prepare(
                        configuration,
                        profile(NetworkPolicy.ALLOW, SandboxWorkspaceAccess.READ_WRITE),
                        workspace,
                        workspace,
                        temporary.resolve("controls"),
                        List.of(),
                        command)
                .argv();

        assertThat(denied).contains("--unshare-all", "--die-with-parent", "--chdir", "/workspace/subdir");
        assertThat(denied).doesNotContain("--share-net");
        assertThat(allowed).contains("--share-net");
        int boundary = denied.lastIndexOf("--");
        assertThat(denied.subList(boundary + 1, denied.size())).containsExactlyElementsOf(command.argv());
    }

    private LocalNativeSandboxConfiguration configuration() {
        return new LocalNativeSandboxConfiguration(
                List.of("/bin/bash", "-lc"),
                temporary.resolve("controls"),
                temporary.resolve("sandbox-exec"),
                temporary.resolve("bwrap"),
                Map.of(),
                Set.of(temporary.resolve("sensitive")));
    }

    private SandboxProfile profile(NetworkPolicy network, SandboxWorkspaceAccess access) {
        LocalNativeSandboxConfiguration configuration = configuration();
        return new SandboxProfile(
                new SandboxProfileRef("local-native-test", "1"),
                LocalNativeSandboxProvider.PROVIDER_ID,
                configuration.digest(),
                Set.of("tool"),
                Set.of(),
                true,
                network,
                new SandboxFilesystemPolicy(access, true, Set.of()),
                new SandboxCapabilities(true, true, network == NetworkPolicy.DENY, false, false));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
