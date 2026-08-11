package io.haifa.agent.execution.core.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalIncrementalWorkspaceChangeObserverTest {
    @TempDir
    Path root;

    @TempDir
    Path outsideRoot;

    @Test
    void reportsOnlyExecutionWindowCandidatesAndIgnoresGeneratedDirectories() throws Exception {
        Files.writeString(root.resolve("before.txt"), "before\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("delete.txt"), "delete\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("move.txt"), "unique-move-content\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve(".pytest_cache"));
        Files.writeString(root.resolve(".pytest_cache/ignored.txt"), "ignored\n", StandardCharsets.UTF_8);
        WorkspaceId workspaceId = new WorkspaceId("incremental-test");
        var observer = new LocalIncrementalWorkspaceChangeObserver(
                workspaceId,
                root,
                (path, type) -> path.segments().contains(".pytest_cache")
                        && (type == FileType.DIRECTORY || path.segments().size() > 1));

        var first = observer.begin(workspaceId);
        Files.writeString(root.resolve("before.txt"), "after\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/new.txt"), "new\n", StandardCharsets.UTF_8);
        Files.delete(root.resolve("delete.txt"));
        Files.move(root.resolve("move.txt"), root.resolve("moved.txt"));
        Files.writeString(root.resolve(".pytest_cache/ignored.txt"), "changed\n", StandardCharsets.UTF_8);
        var changes = first.complete();

        assertThat(changes)
                .anySatisfy(change -> {
                    assertThat(change.type()).isEqualTo(FileChangeType.REPLACE);
                    assertThat(change.path().toString()).isEqualTo("before.txt");
                })
                .anySatisfy(change -> {
                    assertThat(change.type()).isEqualTo(FileChangeType.CREATE);
                    assertThat(change.path().toString()).isEqualTo("src/new.txt");
                })
                .anySatisfy(change -> {
                    assertThat(change.type()).isEqualTo(FileChangeType.DELETE);
                    assertThat(change.path().toString()).isEqualTo("delete.txt");
                })
                .anySatisfy(change -> {
                    assertThat(change.type()).isEqualTo(FileChangeType.MOVE);
                    assertThat(change.path().toString()).isEqualTo("move.txt");
                    assertThat(change.destination()).hasToString("moved.txt");
                });
        assertThat(changes).noneMatch(change -> change.path().toString().contains(".pytest_cache"));

        var second = observer.begin(workspaceId);
        assertThat(second.complete()).isEmpty();
        observer.close();
    }

    @Test
    void rejectsAWorkspaceOtherThanItsExplicitBinding() {
        var observer = new LocalIncrementalWorkspaceChangeObserver(
                new WorkspaceId("expected"), root, WorkspaceChangeIgnorePolicy.none());

        assertThatThrownBy(() -> observer.begin(new WorkspaceId("other")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WORKSPACE_OBSERVER_BINDING_MISMATCH");
        observer.close();
    }

    @Test
    void observationCanOnlyBeCompletedOnce() {
        WorkspaceId workspaceId = new WorkspaceId("single-completion-test");
        var observer =
                new LocalIncrementalWorkspaceChangeObserver(workspaceId, root, WorkspaceChangeIgnorePolicy.none());
        var observation = observer.begin(workspaceId);

        assertThat(observation.complete()).isEmpty();
        assertThatThrownBy(observation::complete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already completed");
        observer.close();
    }

    @Test
    void closeReleasesResourcesAndAllowsAFreshBaseline() throws Exception {
        WorkspaceId workspaceId = new WorkspaceId("close-reset-test");
        var observer =
                new LocalIncrementalWorkspaceChangeObserver(workspaceId, root, WorkspaceChangeIgnorePolicy.none());
        assertThat(observer.begin(workspaceId).complete()).isEmpty();
        observer.close();
        Files.writeString(root.resolve("after-close.txt"), "fresh baseline");

        assertThat(observer.begin(workspaceId).complete()).isEmpty();
        observer.close();
    }

    @Test
    void cancelReleasesTheWindowAndAbsorbsChanges() throws Exception {
        WorkspaceId workspaceId = new WorkspaceId("cancel-test");
        var observer = new LocalIncrementalWorkspaceChangeObserver(
                workspaceId,
                root,
                WorkspaceChangeIgnorePolicy.none(),
                LocalIncrementalWorkspaceChangeObserverTest::deterministicVersion,
                false);
        var cancelled = observer.begin(workspaceId);
        Files.writeString(root.resolve("cancelled.txt"), "ignored as a prior window\n");
        cancelled.cancel();

        var next = observer.begin(workspaceId);
        assertThat(next.complete()).isEmpty();
        observer.close();
    }

    @Test
    void invalidatedDirectoryKeyTriggersSafeWorkspaceResynchronization() throws Exception {
        Path directory = Files.createDirectory(root.resolve("removed-directory"));
        Files.writeString(directory.resolve("removed.txt"), "before");
        WorkspaceId workspaceId = new WorkspaceId("key-invalidation-test");
        var observer =
                new LocalIncrementalWorkspaceChangeObserver(workspaceId, root, WorkspaceChangeIgnorePolicy.none());
        var observation = observer.begin(workspaceId);

        Files.delete(directory.resolve("removed.txt"));
        Files.delete(directory);
        var changes = observation.complete();

        assertThat(changes).anySatisfy(change -> {
            assertThat(change.type()).isEqualTo(FileChangeType.DELETE);
            assertThat(change.path()).hasToString("removed-directory/removed.txt");
        });
        assertThat(observer.begin(workspaceId).complete()).isEmpty();
        observer.close();
    }

    @Test
    void unchangedSecondWindowDoesNotRehashAnUnchangedLargeFile() throws Exception {
        Path large = root.resolve("large.bin");
        Files.write(large, new byte[2 * 1024 * 1024]);
        WorkspaceId workspaceId = new WorkspaceId("incremental-hash-test");
        AtomicInteger hashes = new AtomicInteger();
        LocalIncrementalWorkspaceChangeObserver.FileVersionResolver versions = (file, attributes) -> {
            hashes.incrementAndGet();
            return deterministicVersion(file, attributes);
        };
        var observer = new LocalIncrementalWorkspaceChangeObserver(
                workspaceId, root, WorkspaceChangeIgnorePolicy.none(), versions);

        var first = observer.begin(workspaceId);
        assertThat(first.complete()).isEmpty();
        int baselineHashes = hashes.get();
        var second = observer.begin(workspaceId);
        assertThat(second.complete()).isEmpty();

        assertThat(baselineHashes).isEqualTo(1);
        assertThat(hashes).hasValue(baselineHashes);
        observer.close();
    }

    @Test
    void opaqueFileKeysUseTheirStableRepresentation() {
        assertThat(LocalIncrementalWorkspaceChangeObserver.stableFileKey(new OpaqueFileKey("same")))
                .isEqualTo(LocalIncrementalWorkspaceChangeObserver.stableFileKey(new OpaqueFileKey("same")));
        assertThat(LocalIncrementalWorkspaceChangeObserver.stableFileKey(null)).isNull();
    }

    @Test
    void symbolicLinkCannotExpandObservationOutsideTheWorkspace() throws Exception {
        Path external = Files.writeString(outsideRoot.resolve("external.txt"), "before");
        try {
            Files.createSymbolicLink(root.resolve("outside-link"), outsideRoot);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
            Assumptions.abort("symbolic links are unavailable for this test host");
        }
        WorkspaceId workspaceId = new WorkspaceId("symlink-boundary-test");
        var observer =
                new LocalIncrementalWorkspaceChangeObserver(workspaceId, root, WorkspaceChangeIgnorePolicy.none());

        var observation = observer.begin(workspaceId);
        Files.writeString(external, "after");

        assertThat(observation.complete()).isEmpty();
        observer.close();
    }

    private static io.haifa.agent.project.changeset.FileVersion deterministicVersion(
            Path file, BasicFileAttributes attributes) {
        return new io.haifa.agent.project.changeset.FileVersion(
                FileType.FILE, attributes.size(), "test:" + file.getFileName() + ":" + attributes.size());
    }

    private static final class OpaqueFileKey {
        private final String value;

        private OpaqueFileKey(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
