package io.haifa.agent.testing.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryRevisionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void inspectsCleanCommitAndDetectsUntrackedFiles() throws Exception {
        Path repository = committedRepository(temporaryDirectory.resolve("repository"));

        RepositoryRevision clean = RepositoryRevision.inspect(repository);
        assertFalse(clean.dirty());
        assertEquals(40, clean.commit().length());
        clean.requireClean("test repository");
        clean.requireCommit(clean.commit(), "test repository");

        Files.writeString(repository.resolve("untracked.txt"), "not committed");
        RepositoryRevision dirty = RepositoryRevision.inspect(repository);
        assertTrue(dirty.dirty());
        assertEquals(clean.commit(), dirty.commit());
        assertThrows(IllegalArgumentException.class, () -> dirty.requireClean("test repository"));
        assertThrows(
                IllegalArgumentException.class,
                () -> clean.requireCommit("0000000000000000000000000000000000000000", "test repository"));
        assertThrows(IllegalArgumentException.class, () -> clean.requireUnchanged(dirty, "test repository"));
    }

    @Test
    void rejectsDirectoryNestedInsideAnotherRepository() throws Exception {
        Path repository = committedRepository(temporaryDirectory.resolve("outer"));
        Path nested = Files.createDirectory(repository.resolve("nested"));

        assertThrows(IllegalArgumentException.class, () -> RepositoryRevision.inspect(nested));
    }

    @Test
    void acceptsAncestorBaselineAndRejectsDivergentCommit() throws Exception {
        Path repository = committedRepository(temporaryDirectory.resolve("repository"));
        RepositoryRevision baseline = RepositoryRevision.inspect(repository);

        Files.writeString(repository.resolve("compatible.txt"), "compatible");
        runGit(repository, "add", "compatible.txt");
        runGit(repository, "commit", "--quiet", "-m", "compatible");
        RepositoryRevision compatibleHead = RepositoryRevision.inspect(repository);

        compatibleHead.requireCompatibleBaseline(repository, baseline.commit(), "test matrix");
        compatibleHead.requireCompatibleBaseline(repository, compatibleHead.commit(), "test matrix");

        runGit(repository, "checkout", "--quiet", "--detach", baseline.commit());
        Files.writeString(repository.resolve("divergent.txt"), "divergent");
        runGit(repository, "add", "divergent.txt");
        runGit(repository, "commit", "--quiet", "-m", "divergent");
        String divergentCommit = RepositoryRevision.inspect(repository).commit();
        runGit(repository, "checkout", "--quiet", "--detach", compatibleHead.commit());

        RepositoryRevision restoredHead = RepositoryRevision.inspect(repository);
        assertThrows(
                IllegalArgumentException.class,
                () -> restoredHead.requireCompatibleBaseline(repository, divergentCommit, "test matrix"));
    }

    private static Path committedRepository(Path repository) throws Exception {
        Files.createDirectories(repository);
        runGit(repository, "init", "--quiet");
        runGit(repository, "config", "user.email", "tests@haifa.invalid");
        runGit(repository, "config", "user.name", "Haifa Tests");
        Files.writeString(repository.resolve("tracked.txt"), "tracked");
        runGit(repository, "add", "tracked.txt");
        runGit(repository, "commit", "--quiet", "-m", "initial");
        return repository;
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
