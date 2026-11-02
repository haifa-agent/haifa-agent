package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliHostConfigurationTest {
    private static final Path WORKSPACE_CONFIGURATION = Path.of(".haifa-agent").resolve("config.yaml");

    @TempDir
    Path directory;

    @Test
    void hostConfigurationIgnoresWorkspaceOverlayWhileEmbeddedKeepsIt() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Path workspaceConfiguration =
                Files.createDirectories(workspace.resolve(".haifa-agent")).resolve("config.yaml");
        Files.writeString(workspaceConfiguration, "approval:\n  mode: deny\n");
        Path userConfiguration = Files.writeString(directory.resolve("user.yaml"), "approval:\n  mode: auto\n");
        CliArguments arguments = CliArguments.parse(new String[] {"-m", "host-configuration"});
        CliConfigurationLoader loader = new CliConfigurationLoader();

        CliConfiguration host =
                loader.loadForHost(arguments, workspace, Optional.of(userConfiguration), WORKSPACE_CONFIGURATION);
        CliConfiguration embedded =
                loader.load(arguments, workspace, Optional.of(userConfiguration), WORKSPACE_CONFIGURATION);

        assertThat(host.approval()).isEqualTo(ApprovalMode.AUTO);
        assertThat(embedded.approval()).isEqualTo(ApprovalMode.DENY);
    }

    @Test
    void hostConfigurationWithoutUserFileFallsBackToDefaultsNotWorkspace() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace-without-user-config"));
        Path workspaceConfiguration =
                Files.createDirectories(workspace.resolve(".haifa-agent")).resolve("config.yaml");
        Files.writeString(workspaceConfiguration, "approval:\n  mode: deny\n");
        CliArguments arguments = CliArguments.parse(new String[] {"-m", "host-defaults"});

        CliConfiguration host = new CliConfigurationLoader()
                .loadForHost(arguments, workspace, Optional.empty(), WORKSPACE_CONFIGURATION);

        assertThat(host.approval()).isEqualTo(CliConfiguration.defaults().approval());
    }

    @Test
    void explicitHostConfigurationStillOverridesUserAndWorkspaceFiles() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace-with-explicit-config"));
        Path workspaceConfiguration =
                Files.createDirectories(workspace.resolve(".haifa-agent")).resolve("config.yaml");
        Files.writeString(workspaceConfiguration, "approval:\n  mode: auto\n");
        Path userConfiguration = Files.writeString(directory.resolve("user.yaml"), "approval:\n  mode: auto\n");
        Path explicitConfiguration = Files.writeString(directory.resolve("explicit.yaml"), "approval:\n  mode: deny\n");
        CliArguments arguments =
                CliArguments.parse(new String[] {"-m", "host-explicit", "--config", explicitConfiguration.toString()});

        CliConfiguration host = new CliConfigurationLoader()
                .loadForHost(arguments, workspace, Optional.of(userConfiguration), WORKSPACE_CONFIGURATION);

        assertThat(host.approval()).isEqualTo(ApprovalMode.DENY);
    }
}
