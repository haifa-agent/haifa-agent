package io.haifa.agent.project.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemorySessionChangeLedgerTest {

    private InMemorySessionChangeLedger ledger;
    private WorkspaceId mainWorkspace;
    private WorkspaceId docsWorkspace;
    private Instant now;

    @BeforeEach
    void setUp() {
        ledger = new InMemorySessionChangeLedger();
        mainWorkspace = new WorkspaceId("ws-main");
        docsWorkspace = new WorkspaceId("ws-docs");
        now = Instant.parse("2026-09-01T12:00:00Z");
    }

    @Test
    void mergesMultipleWritesOnSameFile() {
        WorkspacePath path = new WorkspacePath(mainWorkspace, ProjectPath.of("src/App.java"));
        ledger.record(SessionFileChangeRecord.replace(path, "hash0", 10, "hash1", 15, now));
        ledger.record(SessionFileChangeRecord.replace(path, "hash1", 15, "hash2", 20, now.plusSeconds(1)));

        List<SessionFileChangeRecord> compacted = ledger.compactedChanges(mainWorkspace);
        assertThat(compacted).hasSize(1);
        SessionFileChangeRecord change = compacted.get(0);
        assertThat(change.path()).isEqualTo(path);
        assertThat(change.type()).isEqualTo(FileChangeType.REPLACE);
        assertThat(change.beforeHash()).isEqualTo("hash0");
        assertThat(change.beforeSize()).isEqualTo(10L);
        assertThat(change.afterHash()).isEqualTo("hash2");
        assertThat(change.afterSize()).isEqualTo(20L);
    }

    @Test
    void createFollowedByWriteKeepsCreateTypeWithLatestContent() {
        WorkspacePath path = new WorkspacePath(mainWorkspace, ProjectPath.of("README.md"));
        ledger.record(SessionFileChangeRecord.create(path, "hash-init", 5, now));
        ledger.record(SessionFileChangeRecord.replace(path, "hash-init", 5, "hash-final", 12, now.plusSeconds(1)));

        List<SessionFileChangeRecord> compacted = ledger.compactedChanges(mainWorkspace);
        assertThat(compacted).hasSize(1);
        SessionFileChangeRecord change = compacted.get(0);
        assertThat(change.type()).isEqualTo(FileChangeType.CREATE);
        assertThat(change.beforeHash()).isNull();
        assertThat(change.afterHash()).isEqualTo("hash-final");
        assertThat(change.afterSize()).isEqualTo(12L);
    }

    @Test
    void createFollowedByDeleteCancelsOut() {
        WorkspacePath path = new WorkspacePath(mainWorkspace, ProjectPath.of("temp.txt"));
        ledger.record(SessionFileChangeRecord.create(path, "hash1", 10, now));
        ledger.record(SessionFileChangeRecord.delete(path, "hash1", 10, now.plusSeconds(1)));

        List<SessionFileChangeRecord> compacted = ledger.compactedChanges(mainWorkspace);
        assertThat(compacted).isEmpty();
    }

    @Test
    void moveChainTracksOriginalSourceAndLatestTarget() {
        WorkspacePath a = new WorkspacePath(mainWorkspace, ProjectPath.of("a.txt"));
        WorkspacePath b = new WorkspacePath(mainWorkspace, ProjectPath.of("b.txt"));
        WorkspacePath c = new WorkspacePath(mainWorkspace, ProjectPath.of("c.txt"));

        ledger.record(SessionFileChangeRecord.move(a, b, "hashA", 10, "hashA", 10, now));
        ledger.record(SessionFileChangeRecord.replace(b, "hashA", 10, "hashB", 15, now.plusSeconds(1)));
        ledger.record(SessionFileChangeRecord.move(b, c, "hashB", 15, "hashB", 15, now.plusSeconds(2)));

        List<SessionFileChangeRecord> compacted = ledger.compactedChanges(mainWorkspace);
        assertThat(compacted).hasSize(1);
        SessionFileChangeRecord change = compacted.get(0);
        assertThat(change.type()).isEqualTo(FileChangeType.MOVE);
        assertThat(change.sourcePath()).isEqualTo(a);
        assertThat(change.path()).isEqualTo(c);
        assertThat(change.beforeHash()).isEqualTo("hashA");
        assertThat(change.afterHash()).isEqualTo("hashB");
    }

    @Test
    void isolatesChangesAcrossWorkspacesEvenForSameRelativeFileName() {
        WorkspacePath inMain = new WorkspacePath(mainWorkspace, ProjectPath.of("notes.md"));
        WorkspacePath inDocs = new WorkspacePath(docsWorkspace, ProjectPath.of("notes.md"));
        ledger.record(SessionFileChangeRecord.create(inMain, "hashM", 5, now));
        ledger.record(SessionFileChangeRecord.create(inDocs, "hashD", 8, now));

        assertThat(ledger.compactedChanges(mainWorkspace)).hasSize(1);
        assertThat(ledger.compactedChanges(docsWorkspace)).hasSize(1);
        assertThat(ledger.compactedChanges(docsWorkspace).get(0).afterHash()).isEqualTo("hashD");
        assertThat(ledger.allCompactedChanges()).hasSize(2);
    }

    @Test
    void rejectsMoveSpanningTwoWorkspaces() {
        WorkspacePath source = new WorkspacePath(mainWorkspace, ProjectPath.of("a.txt"));
        WorkspacePath target = new WorkspacePath(docsWorkspace, ProjectPath.of("b.txt"));

        assertThatThrownBy(() -> SessionFileChangeRecord.move(source, target, "hashA", 10, "hashA", 10, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two logical workspaces");
    }
}
