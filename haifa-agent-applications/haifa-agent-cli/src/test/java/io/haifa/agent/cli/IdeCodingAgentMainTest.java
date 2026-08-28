package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdeCodingAgentMainTest {
    @Test
    void helpDocumentsTheThreeConfigurationTiers() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        int exit = IdeCodingAgentMain.run(
                new String[] {"--help"}, new PrintStream(bytes, true, StandardCharsets.UTF_8), output());

        assertThat(exit).isZero();
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertThat(text)
                .contains("Usage: IdeCodingAgentMain")
                .contains("FROZEN")
                .contains(".haifa-agent/coding/ide-config.yaml")
                .contains("--config <path>")
                .contains("replaces automatic user/workspace discovery")
                .contains("--trace <mode>")
                .contains("--trace-file <path>")
                .contains("RUNTIME");
    }

    @Test
    void loadsUserIdeConfigurationBeforeWorkspaceIdeConfiguration(@TempDir Path temporaryDirectory) throws Exception {
        Path userConfiguration = temporaryDirectory.resolve("user-ide-config.yaml");
        Files.writeString(userConfiguration, "approval:\n  mode: auto\n", StandardCharsets.UTF_8);
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path workspaceConfiguration = workspace.resolve(".haifa-agent/coding/ide-config.yaml");
        Files.createDirectories(workspaceConfiguration.getParent());
        Files.writeString(workspaceConfiguration, "runtime:\n  maxIterations: 7\n", StandardCharsets.UTF_8);

        CliConfiguration configuration = new CliConfigurationLoader()
                .load(
                        CliArguments.parse(new String[] {"-m", "inspect"}),
                        workspace,
                        java.util.Optional.of(userConfiguration),
                        Path.of(".haifa-agent/coding/ide-config.yaml"));

        assertThat(configuration.approval()).isEqualTo(ApprovalMode.AUTO);
        assertThat(configuration.maxIterations()).isEqualTo(7);
    }

    @Test
    void resolvesConfigurationOnceAndRunsTheExactResolvedAssembly() {
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<CliArguments> executedArguments = new AtomicReference<>();
        AtomicReference<Path> executedWorkspace = new AtomicReference<>();
        AtomicReference<CliConfiguration> executedConfiguration = new AtomicReference<>();
        CliConfiguration defaults = CliConfiguration.defaults();
        ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();

        int exit = IdeCodingAgentMain.run(
                new String[] {"-m", "inspect"},
                new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
                output(),
                (arguments, workspace) -> {
                    loads.incrementAndGet();
                    return defaults;
                },
                (arguments, workspace, configuration, output, error) -> {
                    executedArguments.set(arguments);
                    executedWorkspace.set(workspace);
                    executedConfiguration.set(configuration);
                    return 7;
                });

        assertThat(exit).isEqualTo(7);
        assertThat(loads).hasValue(1);
        assertThat(executedArguments.get().message()).contains("inspect");
        assertThat(executedWorkspace.get())
                .isEqualTo(Path.of(".").toAbsolutePath().normalize());
        assertThat(executedConfiguration.get()).isSameAs(defaults);
        assertThat(standardOutput.toString(StandardCharsets.UTF_8)).contains("Coding Agent assembly (debug entry)");
    }

    @Test
    void requiresAnExplicitCodingTask() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = IdeCodingAgentMain.run(
                new String[] {"--workspace", "somewhere"},
                output(),
                new PrintStream(error, true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(1);
        assertThat(error.toString(StandardCharsets.UTF_8)).contains("-m/--message");
    }

    @Test
    void rejectsTerminalAndResumeModesAsOneShotOnlyEntry() {
        assertThat(IdeCodingAgentMain.run(new String[] {"--terminal"}, output(), output()))
                .isEqualTo(1);
        assertThat(IdeCodingAgentMain.run(new String[] {"resume"}, output(), output()))
                .isEqualTo(1);
    }

    @Test
    void summaryDescribesResolvedAssemblyWithoutSecrets() {
        CliConfiguration defaults = CliConfiguration.defaults();

        String text = IdeCodingAgentMain.summary(defaults, Path.of("."));

        assertThat(text)
                .contains("model")
                .contains("deepseek")
                .contains("execution")
                .contains("approval")
                .contains("config tiers");
        assertThat(text)
                .doesNotContain("credentialRef")
                .doesNotContain("api.deepseek.com")
                .doesNotContain("DEEPSEEK_API_KEY");
    }

    private static PrintStream output() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }
}
