package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustedWorkspaceEnvironmentCatalogTest {
    @TempDir
    Path root;

    @Test
    void emptyWorkspaceProducesExplicitBoundedUnknownFacts() {
        var snapshot = capture(disabledEnvironment());

        assertThat(snapshot.gitRepositoryStatus())
                .isEqualTo(TrustedWorkspaceEnvironmentCatalog.RepositoryStatus.NOT_PRESENT);
        assertThat(snapshot.instructionStatus()).isEqualTo(TrustedProjectResourceCatalog.InstructionStatus.NOT_PRESENT);
        assertThat(snapshot.projectSignals()).isEmpty();
        assertThat(snapshot.validationCandidates()).isEmpty();
        assertThat(snapshot.promptBlock())
                .contains(
                        "<workspace_root>.</workspace_root>",
                        "enabled=\"false\"",
                        "network=\"UNAVAILABLE\"",
                        "<project_signals>NONE</project_signals>",
                        "dirty=\"NOT_PROBED\"",
                        "executables and versions were not probed")
                .doesNotContain(root.toString());
        assertThat(snapshot.promptBlock().getBytes(StandardCharsets.UTF_8))
                .hasSizeLessThanOrEqualTo(TrustedWorkspaceEnvironmentCatalog.MAXIMUM_PROMPT_BYTES);
    }

    @Test
    void mixedRootReusesStableSignalsAndFrozenVerificationCandidates() throws Exception {
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve("AGENTS.md"), "Preserve unrelated changes.");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("mvnw.cmd"), "wrapper");
        Files.writeString(root.resolve("package.json"), "{}");
        Files.writeString(root.resolve("package-lock.json"), "{}");
        Files.writeString(root.resolve("pyproject.toml"), "[tool.pytest.ini_options]");
        Files.createDirectories(root.resolve("src/test"));

        var discovery = CliVerificationProfileDiscovery.discoverWithSignals(root, "Windows 11");
        var snapshot = new TrustedWorkspaceEnvironmentCatalog(
                        root, discovery, new TrustedProjectResourceCatalog(root).snapshot(), enabledEnvironment())
                .snapshot();

        assertThat(snapshot.gitRepositoryStatus())
                .isEqualTo(TrustedWorkspaceEnvironmentCatalog.RepositoryStatus.PRESENT);
        assertThat(snapshot.instructionStatus()).isEqualTo(TrustedProjectResourceCatalog.InstructionStatus.PRESENT);
        assertThat(snapshot.projectSignals())
                .containsExactly(
                        "mvnw.cmd", "package-lock.json", "package.json", "pom.xml", "pyproject.toml", "src/test");
        assertThat(snapshot.validationCandidates()).containsExactly(".\\mvnw.cmd test", "npm test", "python -m pytest");
        assertThat(snapshot.promptBlock())
                .contains("network=\"DENY\"", "root_agents=\"PRESENT\"", ".\\mvnw.cmd test,npm test,python -m pytest")
                .doesNotContain("Preserve unrelated changes.");
        assertThat(snapshot.truncated()).isFalse();
    }

    @Test
    void gitFileIsPresentButSymbolicLinkIsInvalid() throws Exception {
        Files.writeString(root.resolve(".git"), "gitdir: ../worktrees/example");
        assertThat(capture(disabledEnvironment()).gitRepositoryStatus())
                .isEqualTo(TrustedWorkspaceEnvironmentCatalog.RepositoryStatus.PRESENT);

        Files.delete(root.resolve(".git"));
        Path target = Files.createDirectory(root.resolve("actual-git"));
        createSymbolicLinkOrSkip(root.resolve(".git"), target);

        var invalid = capture(disabledEnvironment());
        assertThat(invalid.gitRepositoryStatus())
                .isEqualTo(TrustedWorkspaceEnvironmentCatalog.RepositoryStatus.INVALID);
        assertThat(invalid.diagnostics()).contains("git-root-marker:INVALID");
    }

    @Test
    void escapesDynamicFactsAndNeverIncludesTheHostWorkspacePath() {
        var environment = new TrustedWorkspaceEnvironmentCatalog.EnvironmentFacts(
                "Example & OS",
                "1<2",
                "arch\"64",
                "Java '21'",
                "PowerShell & safe",
                true,
                TrustedWorkspaceEnvironmentCatalog.NetworkPolicyFact.ALLOW,
                "1000",
                "2000",
                TrustedWorkspaceEnvironmentCatalog.TemporarySpaceFact.SANDBOX_MANAGED);

        var snapshot = capture(environment);

        assertThat(snapshot.promptBlock())
                .contains("Example &amp; OS", "1&lt;2", "arch&quot;64", "Java &apos;21&apos;", "PowerShell &amp; safe")
                .doesNotContain(root.toString(), System.getProperty("user.home", "__missing_home__"));
        assertThat(snapshot.toString()).doesNotContain(root.toString());
    }

    @Test
    void deterministicallyTruncatesOversizedProjectionWithoutBreakingByteBudget() {
        List<String> oversizedSignals = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> "signal-" + index + "-" + "x".repeat(500))
                .toList();
        var discovery = new CliVerificationProfileDiscovery.DiscoveryResult(
                CodingVerificationProfile.empty(), oversizedSignals, List.of());
        var resources = new TrustedProjectResourceCatalog(root).snapshot();

        var first = new TrustedWorkspaceEnvironmentCatalog(root, discovery, resources, enabledEnvironment()).snapshot();
        var second =
                new TrustedWorkspaceEnvironmentCatalog(root, discovery, resources, enabledEnvironment()).snapshot();

        assertThat(first.truncated()).isTrue();
        assertThat(first.promptBlock()).isEqualTo(second.promptBlock()).contains("truncated=\"true\"");
        assertThat(first.promptBlock().getBytes(StandardCharsets.UTF_8))
                .hasSizeLessThanOrEqualTo(TrustedWorkspaceEnvironmentCatalog.MAXIMUM_PROMPT_BYTES);
    }

    @Test
    void projectInstructionReloadOnlyChangesFutureEnvironmentSnapshot() throws Exception {
        var resources = new TrustedProjectResourceCatalog(root);
        var catalog = new TrustedWorkspaceEnvironmentCatalog(
                root,
                CliVerificationProfileDiscovery.discoverWithSignals(root, "Linux"),
                resources.snapshot(),
                disabledEnvironment());
        var first = catalog.snapshot();

        Files.writeString(root.resolve("AGENTS.md"), "Future runs only.");
        var future = catalog.snapshot(resources.reload());

        assertThat(first.instructionStatus()).isEqualTo(TrustedProjectResourceCatalog.InstructionStatus.NOT_PRESENT);
        assertThat(first.promptBlock()).contains("root_agents=\"NOT_PRESENT\"");
        assertThat(future.generation()).isGreaterThan(first.generation());
        assertThat(future.instructionStatus()).isEqualTo(TrustedProjectResourceCatalog.InstructionStatus.PRESENT);
        assertThat(future.promptBlock()).contains("root_agents=\"PRESENT\"").doesNotContain("Future runs only.");
    }

    private TrustedWorkspaceEnvironmentCatalog.Snapshot capture(
            TrustedWorkspaceEnvironmentCatalog.EnvironmentFacts environment) {
        return new TrustedWorkspaceEnvironmentCatalog(
                        root,
                        CliVerificationProfileDiscovery.discoverWithSignals(root, "Linux"),
                        new TrustedProjectResourceCatalog(root).snapshot(),
                        environment)
                .snapshot();
    }

    private static TrustedWorkspaceEnvironmentCatalog.EnvironmentFacts disabledEnvironment() {
        return TrustedWorkspaceEnvironmentCatalog.EnvironmentFacts.capture(
                "unavailable", false, "UNAVAILABLE", Duration.ofSeconds(30), Duration.ofMinutes(10));
    }

    private static TrustedWorkspaceEnvironmentCatalog.EnvironmentFacts enabledEnvironment() {
        return TrustedWorkspaceEnvironmentCatalog.EnvironmentFacts.capture(
                "PowerShell", true, "DENY", Duration.ofSeconds(30), Duration.ofMinutes(10));
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
