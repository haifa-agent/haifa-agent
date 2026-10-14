package io.haifa.agent.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.execution.core.tool.TrustedSkillScriptPackageDirectory.PackageFile;
import io.haifa.agent.execution.core.tool.TrustedSkillScriptPackageDirectory.PreparedSkillPackage;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R8: two independent platform instances that declare the same workspace prepare the same Skill
 * package version without destroying each other's published directory. The losing preparer writes
 * into a process-unique temporary directory and must never remove or rebuild a completed version
 * directory; an interrupted preparation never leaves a final directory behind.
 */
class SkillScriptPackageDirectoryTest {
    private static final String VERSION_KEY = "a".repeat(64);
    private static final String ENTRY = "scripts/main";
    private static final String HELPER = "scripts/helpers/note.txt";
    private static final String MARKER = "helper marker v1";

    @TempDir
    Path temporary;

    private final List<PackageFile> files = List.of(
            new PackageFile(ENTRY, "echo ok".getBytes(StandardCharsets.UTF_8)),
            new PackageFile(HELPER, MARKER.getBytes(StandardCharsets.UTF_8)));
    private ExecutorService executor;

    @BeforeEach
    void startExecutor() {
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stopExecutor() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Deterministic race: instance A enters preparation first but pauses inside its verified
     * package supplier; instance B completes and publishes the same version; A then resumes and
     * must keep B's completed directory with all of B's bytes - not delete or rebuild it.
     */
    @Test
    void aLosingPreparerNeverRemovesTheCompletedDirectoryOfAnotherInstance() throws Exception {
        Path workspace = prepWorkspace("workspace-one");
        CountDownLatch enteredByA = new CountDownLatch(1);
        CountDownLatch finishedByB = new CountDownLatch(1);
        var blockedSupplier = new java.util.function.Supplier<List<PackageFile>>() {
            @Override
            public List<PackageFile> get() {
                enteredByA.countDown();
                try {
                    if (!finishedByB.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the other preparer never finished");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", interrupted);
                }
                return files;
            }
        };

        Future<PreparedSkillPackage> pausedA =
                executor.submit(() -> directory(workspace).prepareIfAbsent(VERSION_KEY, blockedSupplier, ENTRY));
        assertThat(enteredByA.await(10, TimeUnit.SECONDS))
                .as("A observed the version directory as absent and paused inside its supplier")
                .isTrue();

        // B, an independent platform instance, now completes the same version and publishes it.
        PreparedSkillPackage publishedByB = directory(workspace).prepareIfAbsent(VERSION_KEY, () -> files, ENTRY);
        finishedByB.countDown();

        PreparedSkillPackage fromA = pausedA.get(30, TimeUnit.SECONDS);
        Path versionDirectory =
                workspace.resolve(SkillScriptPackageDirectory.DIRECTORY).resolve(VERSION_KEY);
        assertThat(Files.isRegularFile(versionDirectory.resolve(SkillScriptPackageDirectory.PREPARED_MARKER)))
                .isTrue();
        assertThat(Files.readString(versionDirectory.resolve(ENTRY), StandardCharsets.UTF_8))
                .isEqualTo("echo ok");
        assertThat(Files.readString(versionDirectory.resolve(HELPER), StandardCharsets.UTF_8))
                .isEqualTo(MARKER);
        try (var leftovers = Files.list(workspace.resolve(SkillScriptPackageDirectory.DIRECTORY))) {
            assertThat(leftovers.allMatch(name -> name.getFileName().toString().equals(VERSION_KEY)))
                    .as("the losing temporary directory was discarded")
                    .isTrue();
        }
    }

    /** An interrupted preparation leaves no final directory and never poisons later preparation. */
    @Test
    void anInterruptedPreparationLeavesNoFinalDirectoryBehind() throws java.io.IOException {
        Path workspace = prepWorkspace("workspace-two");
        assertThatThrownBy(() -> directory(workspace)
                        .prepareIfAbsent(
                                VERSION_KEY,
                                () -> {
                                    throw new IllegalStateException("the package source failed mid-preparation");
                                },
                                ENTRY))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(
                        workspace.resolve(SkillScriptPackageDirectory.DIRECTORY).resolve(VERSION_KEY)))
                .isFalse();

        directory(workspace).prepareIfAbsent(VERSION_KEY, () -> files, ENTRY);
        Path versionDirectory =
                workspace.resolve(SkillScriptPackageDirectory.DIRECTORY).resolve(VERSION_KEY);
        assertThat(Files.isRegularFile(versionDirectory.resolve(SkillScriptPackageDirectory.PREPARED_MARKER)))
                .isTrue();
        assertThat(Files.isRegularFile(versionDirectory.resolve(ENTRY))).isTrue();
    }

    private HostWorkspaceLocationStore locations;
    private WorkspaceId workspaceId;

    private SkillScriptPackageDirectory directory(Path workspace) {
        if (locations == null) {
            locations = new HostWorkspaceLocationStore();
            workspaceId = new WorkspaceId("race-test");
            locations.register(workspaceId, workspace);
        }
        return new SkillScriptPackageDirectory(
                new SkillScriptPlatformWorkspaceAccess(locations, workspaceId, "SKILL_SCRIPT_UNAVAILABLE"));
    }

    private Path prepWorkspace(String name) throws java.io.IOException {
        return Files.createDirectories(temporary.resolve(name));
    }
}
