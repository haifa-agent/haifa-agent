package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustedProjectResourceCatalogTest {
    @TempDir
    Path root;

    @Test
    void fixedRootInstructionReloadOnlyChangesFutureSnapshot() throws Exception {
        var catalog = new TrustedProjectResourceCatalog(root);
        TrustedProjectResourceCatalog.Snapshot first = catalog.snapshot();
        assertThat(first.instructions()).isEmpty();
        assertThat(first.diagnostics()).singleElement().asString().contains("not loaded");

        Files.writeString(root.resolve("AGENTS.md"), "Use MyBatis and preserve unrelated changes.");
        TrustedProjectResourceCatalog.Snapshot loaded = catalog.reload();

        assertThat(first.instructions()).isEmpty();
        assertThat(loaded.generation()).isGreaterThan(first.generation());
        assertThat(loaded.instructions()).contains("Use MyBatis and preserve unrelated changes.");
        assertThat(loaded.instructionBlock())
                .contains("lowest-precedence", "BEGIN PROJECT AGENTS.md")
                .doesNotContain(root.toString());
        assertThat(loaded.diagnostics()).singleElement().asString().contains("loaded", "sha256:");
    }

    @Test
    void rejectsOversizedInstructionWithoutLeakingHostPath() throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "x".repeat(64 * 1024 + 1));

        TrustedProjectResourceCatalog.Snapshot snapshot = new TrustedProjectResourceCatalog(root).snapshot();

        assertThat(snapshot.instructions()).isEmpty();
        assertThat(snapshot.diagnostics())
                .singleElement()
                .asString()
                .contains("invalid", "64 KiB")
                .doesNotContain(root.toString());
    }
}
