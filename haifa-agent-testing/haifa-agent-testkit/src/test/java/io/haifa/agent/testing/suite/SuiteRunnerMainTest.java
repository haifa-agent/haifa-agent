package io.haifa.agent.testing.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteRunnerMainTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsUninventoriedAssetBeforeLoadingSuite() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("orphan-project"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("orphan-test-config"));
        Path runRoot = temporaryDirectory.resolve("orphan-runs");
        writeInventory(
                projectRoot.resolve("haifa-agent-testing/testing-assets-v2.json"),
                "haifa-agent",
                "haifa-agent-testing/testing-assets-v2.json");
        Path inventory = configRoot.resolve("assets/testing-assets-v2.json");
        Files.createDirectories(inventory.getParent());
        Files.writeString(configRoot.resolve("assets/orphan.txt"), "orphan");
        Files.writeString(
                inventory,
                """
                {
                  "schemaVersion": 2,
                  "repositoryId": "haifa-agent-test-config",
                  "coverageRoots": ["assets"],
                  "assets": [
                    {
                      "assetId": "inventory",
                      "path": "assets/testing-assets-v2.json",
                      "kind": "MANIFEST",
                      "lifecycle": "ACTIVE",
                      "disposition": "KEEP",
                      "owner": "testing-platform",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "formal preflight inventory",
                      "coverageMode": "EXACT"
                    },
                    {
                      "assetId": "asset-root",
                      "path": "assets",
                      "kind": "DIRECTORY",
                      "lifecycle": "ACTIVE",
                      "disposition": "KEEP",
                      "owner": "testing-platform",
                      "referencedBy": ["assets/testing-assets-v2.json"],
                      "replacement": "",
                      "rationale": "directory lifecycle only",
                      "coverageMode": "EXACT"
                    }
                  ]
                }
                """);
        commitRepository(projectRoot);
        commitRepository(configRoot);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> new SuiteRunnerMain()
                .run(
                        new SuiteRunnerMain.Options(
                                projectRoot,
                                configRoot,
                                runRoot,
                                "missing-suite",
                                currentPlatform() + "-primary",
                                false),
                        Map.of()));
        assertTrue(exception.getMessage().contains("not inventoried"));
    }

    @Test
    void plansKnownPrivateSuiteWithoutCreatingRunArtifacts() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("planning-project"));
        Path configRoot = Files.createDirectories(projectRoot.resolve("test-config"));
        Path runRoot = temporaryDirectory.resolve("planning-runs");
        writeRequiredAssetInventories(projectRoot, configRoot);
        Files.createDirectories(configRoot.resolve("suites"));
        Files.createDirectories(configRoot.resolve("matrices"));
        Files.writeString(configRoot.resolve("matrices/primary-v1.yaml"), matrix());
        Files.writeString(
                configRoot.resolve("suites/pr-real-v1.yaml"),
                """
                schemaVersion: 1
                suiteId: pr-real-v1
                matrixRef: primary-v1
                budget:
                  maxWallTimeMinutes: 30
                  maxEstimatedCostUsd: 3
                  maxParallelExternalCalls: 1
                cases:
                  - caseId: CP-01
                    repetitions: 1
                    blocking: true
                  - caseId: CP-09
                    repetitions: 1
                    blocking: true
                """);
        commitRepository(configRoot);
        commitRepository(projectRoot);

        int exitCode = new SuiteRunnerMain()
                .run(
                        new SuiteRunnerMain.Options(
                                projectRoot, configRoot, runRoot, "pr-real-v1", currentPlatform() + "-primary", false),
                        Map.of());

        assertEquals(0, exitCode);
        assertEquals(false, Files.exists(runRoot));
    }

    @Test
    void rejectsIncompatibleEnvironmentDuringPlanOnlyPreflight() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("test-config"));
        Path runRoot = temporaryDirectory.resolve("runs");
        writeRequiredAssetInventories(projectRoot, configRoot);
        Files.createDirectories(configRoot.resolve("suites"));
        Files.createDirectories(configRoot.resolve("matrices"));
        Files.createDirectories(configRoot.resolve("environments/cli"));
        Files.writeString(configRoot.resolve("matrices/primary-v1.yaml"), matrix());
        Files.writeString(
                configRoot.resolve("suites/pr-real-v1.yaml"),
                """
                schemaVersion: 1
                suiteId: pr-real-v1
                matrixRef: primary-v1
                budget:
                  maxWallTimeMinutes: 30
                  maxEstimatedCostUsd: 3
                  maxParallelExternalCalls: 1
                cases:
                  - caseId: CP-01
                    repetitions: 1
                    blocking: true
                """);
        Files.writeString(
                configRoot.resolve("environments/cli/interaction-live.yaml"),
                """
                persistence:
                  maximumPayloadBytes: 4194304
                """);
        commitRepository(projectRoot);
        commitRepository(configRoot);

        assertThrows(IllegalArgumentException.class, () -> new SuiteRunnerMain()
                .run(
                        new SuiteRunnerMain.Options(
                                projectRoot, configRoot, runRoot, "pr-real-v1", currentPlatform() + "-primary", false),
                        Map.of()));
    }

    @Test
    void rejectsDirtyTestConfigBeforeExecute() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("execute-project"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("execute-test-config"));
        Path runRoot = temporaryDirectory.resolve("execute-runs");
        writeRequiredAssetInventories(projectRoot, configRoot);
        Files.createDirectories(configRoot.resolve("suites"));
        Files.createDirectories(configRoot.resolve("matrices"));
        Files.writeString(configRoot.resolve("matrices/primary-v1.yaml"), matrix());
        Files.writeString(
                configRoot.resolve("suites/pr-real-v1.yaml"),
                """
                schemaVersion: 1
                suiteId: pr-real-v1
                matrixRef: primary-v1
                budget:
                  maxWallTimeMinutes: 30
                  maxEstimatedCostUsd: 3
                  maxParallelExternalCalls: 1
                cases:
                  - caseId: CP-01
                    repetitions: 1
                    blocking: true
                """);
        commitRepository(projectRoot);
        commitRepository(configRoot);
        Files.writeString(configRoot.resolve("untracked-override.yaml"), "unsafe: true");

        assertThrows(IllegalArgumentException.class, () -> new SuiteRunnerMain()
                .run(
                        new SuiteRunnerMain.Options(
                                projectRoot, configRoot, runRoot, "pr-real-v1", currentPlatform() + "-primary", true),
                        Map.of()));
        assertEquals(false, Files.exists(runRoot));
    }

    @Test
    void rejectsExecuteWhenSuiteEstimatedCostExceedsIndependentApproval() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("cost-project"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("cost-test-config"));
        Path runRoot = temporaryDirectory.resolve("cost-runs");
        writeRequiredAssetInventories(projectRoot, configRoot);
        Files.createDirectories(configRoot.resolve("suites"));
        Files.createDirectories(configRoot.resolve("matrices"));
        Files.writeString(configRoot.resolve("matrices/primary-v1.yaml"), matrix());
        Files.writeString(
                configRoot.resolve("suites/pr-real-v1.yaml"),
                """
                schemaVersion: 1
                suiteId: pr-real-v1
                matrixRef: primary-v1
                budget:
                  maxWallTimeMinutes: 30
                  maxEstimatedCostUsd: 3
                  maxParallelExternalCalls: 1
                cases:
                  - caseId: CP-01
                    repetitions: 1
                    blocking: true
                """);
        commitRepository(projectRoot);
        commitRepository(configRoot);

        assertThrows(IllegalArgumentException.class, () -> new SuiteRunnerMain()
                .run(
                        new SuiteRunnerMain.Options(
                                projectRoot, configRoot, runRoot, "pr-real-v1", currentPlatform() + "-primary", true),
                        Map.of(
                                "DEEPSEEK_API_KEY",
                                "test-only-placeholder",
                                "HAIFA_TEST_APPROVED_MAX_ESTIMATED_COST_USD",
                                "2.99")));
        assertEquals(false, Files.exists(runRoot));
    }

    @Test
    void rejectsExecuteWhenResolvedPlanDoesNotMatchIndependentApproval() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("plan-project"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("plan-test-config"));
        Path runRoot = temporaryDirectory.resolve("plan-runs");
        writeRequiredAssetInventories(projectRoot, configRoot);
        Files.createDirectories(configRoot.resolve("suites"));
        Files.createDirectories(configRoot.resolve("matrices"));
        Files.writeString(configRoot.resolve("matrices/primary-v1.yaml"), matrix());
        Files.writeString(
                configRoot.resolve("suites/pr-real-v1.yaml"),
                """
                schemaVersion: 1
                suiteId: pr-real-v1
                matrixRef: primary-v1
                budget:
                  maxWallTimeMinutes: 30
                  maxEstimatedCostUsd: 3
                  maxParallelExternalCalls: 1
                cases:
                  - caseId: CP-01
                    repetitions: 1
                    blocking: true
                """);
        commitRepository(projectRoot);
        commitRepository(configRoot);

        assertThrows(IllegalArgumentException.class, () -> new SuiteRunnerMain()
                .run(
                        new SuiteRunnerMain.Options(
                                projectRoot, configRoot, runRoot, "pr-real-v1", currentPlatform() + "-primary", true),
                        Map.of(
                                "DEEPSEEK_API_KEY",
                                "test-only-placeholder",
                                "HAIFA_TEST_APPROVED_MAX_ESTIMATED_COST_USD",
                                "3.0",
                                "HAIFA_TEST_APPROVED_PLAN_SHA256",
                                "0000000000000000000000000000000000000000000000000000000000000000")));
        assertEquals(false, Files.exists(runRoot));
    }

    private static void writeRequiredAssetInventories(Path projectRoot, Path configRoot) throws Exception {
        writeInventory(
                projectRoot.resolve("haifa-agent-testing/testing-assets-v2.json"),
                "haifa-agent",
                "haifa-agent-testing/testing-assets-v2.json");
        writeInventory(
                configRoot.resolve("assets/testing-assets-v2.json"),
                "haifa-agent-test-config",
                "assets/testing-assets-v2.json");
    }

    private static void writeInventory(Path inventory, String repositoryId, String inventoryPath) throws Exception {
        Files.createDirectories(inventory.getParent());
        Files.writeString(
                inventory,
                """
                {
                  "schemaVersion": 2,
                  "repositoryId": "%s",
                  "coverageRoots": [],
                  "assets": [
                    {
                      "assetId": "inventory",
                      "path": "%s",
                      "kind": "MANIFEST",
                      "lifecycle": "ACTIVE",
                      "disposition": "KEEP",
                      "owner": "testing-platform",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "required entry-point inventory"
                    }
                  ]
                }
                """
                        .formatted(repositoryId, inventoryPath));
    }

    private static String matrix() {
        return """
                schemaVersion: 1
                matrixId: primary-v1
                strategy: explicit
                combinations:
                  - id: linux-primary
                    platform: linux
                    modelProvider: deepseek
                    modelId: deepseek-v4-pro
                    webProvider: aliyun
                    mcpTarget: utility
                  - id: macos-primary
                    platform: macos
                    modelProvider: deepseek
                    modelId: deepseek-v4-pro
                    webProvider: aliyun
                    mcpTarget: utility
                  - id: windows-primary
                    platform: windows
                    modelProvider: deepseek
                    modelId: deepseek-v4-pro
                    webProvider: aliyun
                    mcpTarget: utility
                """;
    }

    private static String currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        return "linux";
    }

    private static void commitRepository(Path repository) throws Exception {
        Files.writeString(repository.resolve(".repository-fixture"), "test repository");
        runGit(repository, "init", "--quiet");
        runGit(repository, "config", "user.email", "tests@haifa.invalid");
        runGit(repository, "config", "user.name", "Haifa Tests");
        runGit(repository, "add", ".");
        runGit(repository, "commit", "--quiet", "-m", "fixture");
    }

    private static void runGit(Path repository, String... arguments) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IOException(
                    "git test setup failed: " + new String(output, java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
