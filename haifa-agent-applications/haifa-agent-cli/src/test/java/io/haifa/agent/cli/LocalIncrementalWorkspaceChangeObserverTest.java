package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalIncrementalWorkspaceChangeObserverTest {
    @TempDir
    Path root;

    @Test
    void reportsOnlyExecutionWindowCandidatesAndIgnoresGeneratedDirectories() throws Exception {
        Files.writeString(root.resolve("before.txt"), "before\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("delete.txt"), "delete\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("move.txt"), "unique-move-content\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve(".pytest_cache"));
        Files.writeString(root.resolve(".pytest_cache/ignored.txt"), "ignored\n", StandardCharsets.UTF_8);
        var observer = new LocalIncrementalWorkspaceChangeObserver(root, CliWorkspaceManifestIgnorePolicy.load(root));
        WorkspaceId workspaceId = new WorkspaceId("incremental-test");

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
}
