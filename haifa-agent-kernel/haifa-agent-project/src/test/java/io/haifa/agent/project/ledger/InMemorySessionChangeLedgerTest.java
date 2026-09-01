package io.haifa.agent.project.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.root.WorkspaceRootAlias;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemorySessionChangeLedgerTest {

    private InMemorySessionChangeLedger ledger;
    private WorkspaceRootAlias mainAlias;
    private WorkspaceRootAlias docsAlias;
    private Instant now;

    @BeforeEach
    void setUp() {
        ledger = new InMemorySessionChangeLedger();
        mainAlias = WorkspaceRootAlias.MAIN;
        docsAlias = WorkspaceRootAlias.of("docs");
        now = Instant.parse("2026-09-01T12:00:00Z");
    }

    @Test
    void mergesMultipleWritesOnSameFile() {
        ProjectPath path = ProjectPath.of("src/App.java");
        ledger.record(SessionFileChangeRecord.replace(mainAlias, path, "hash0", 10, "hash1", 15, now));
        ledger.record(SessionFileChangeRecord.replace(mainAlias, path, "hash1", 15, "hash2", 20, now.plusSeconds(1)));

        List<SessionFileChangeRecord> compacted = ledger.compactedChanges(mainAlias);
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
        ProjectPath path = ProjectPath.of("README.md");
        ledger.record(SessionFileChangeRecord.create(mainAlias, path, "hash-init", 5, now));
        ledger.record(
                SessionFileChangeRecord.replace(mainAlias, path, "hash-init", 5, "hash-final", 12, now.plusSeconds(1)));

        List<SessionFileChangeRecord> compacted = ledger.compactedChanges(mainAlias);
        assertThat(compacted).hasSize(1);
        SessionFileChangeRecord change = compacted.get(0);
        assertThat(change.type()).isEqualTo(FileChangeType.CREATE);
        assertThat(change.beforeHash()).isNull();
        assertThat(change.afterHash()).isEqualTo("hash-final");
        assertThat(change.afterSize()).isEqualTo(12L);
    }

    @Test
    void createFollowedByDeleteCancelsOut() {
        ProjectPath path = ProjectPath.of("temp.txt");
        ledger.record(SessionFileChangeRecord.create(mainAlias, path, "hash1", 10, now));
        ledger.record(SessionFileChangeRecord.delete(mainAlias, path, "hash1", 10, now.plusSeconds(1)));

        List<SessionFileChangeRecord> compacted = ledger.compactedChanges(mainAlias);
        assertThat(compacted).isEmpty();
    }

    @Test
    void moveChainTracksOriginalSourceAndLatestTarget() {
        ProjectPath a = ProjectPath.of("a.txt");
        ProjectPath b = ProjectPath.of("b.txt");
        ProjectPath c = ProjectPath.of("c.txt");

        ledger.record(SessionFileChangeRecord.move(mainAlias, a, b, "hashA", 10, "hashA", 10, now));
        ledger.record(SessionFileChangeRecord.replace(mainAlias, b, "hashA", 10, "hashB", 15, now.plusSeconds(1)));
        ledger.record(SessionFileChangeRecord.move(mainAlias, b, c, "hashB", 15, "hashB", 15, now.plusSeconds(2)));

        List<SessionFileChangeRecord> compacted = ledger.compactedChanges(mainAlias);
        assertThat(compacted).hasSize(1);
        SessionFileChangeRecord change = compacted.get(0);
        assertThat(change.type()).isEqualTo(FileChangeType.MOVE);
        assertThat(change.sourcePath()).isEqualTo(a);
        assertThat(change.path()).isEqualTo(c);
        assertThat(change.beforeHash()).isEqualTo("hashA");
        assertThat(change.afterHash()).isEqualTo("hashB");
    }

    @Test
    void isolatesChangesAcrossRoots() {
        ledger.record(SessionFileChangeRecord.create(mainAlias, ProjectPath.of("main.txt"), "hashM", 5, now));
        ledger.record(SessionFileChangeRecord.create(docsAlias, ProjectPath.of("guide.md"), "hashD", 8, now));

        assertThat(ledger.compactedChanges(mainAlias)).hasSize(1);
        assertThat(ledger.compactedChanges(docsAlias)).hasSize(1);
        assertThat(ledger.allCompactedChanges()).hasSize(2);
    }
}
