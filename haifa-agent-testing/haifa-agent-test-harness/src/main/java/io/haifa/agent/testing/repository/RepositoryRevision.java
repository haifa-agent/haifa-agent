package io.haifa.agent.testing.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Immutable Git revision and worktree state used to bind reproducible test evidence. */
public record RepositoryRevision(String commit, boolean dirty) {
    private static final long GIT_TIMEOUT_SECONDS = 30;

    public RepositoryRevision {
        commit = Objects.requireNonNull(commit, "commit must not be null");
        if (!commit.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("commit must be a full lowercase Git commit");
        }
    }

    public static RepositoryRevision inspect(Path repository) throws IOException, InterruptedException {
        Path root = requireRepositoryRoot(repository);
        return inspectRoot(root);
    }

    private static RepositoryRevision inspectRoot(Path root) throws IOException, InterruptedException {
        String commit = runGit(root, "rev-parse", "HEAD").toLowerCase(Locale.ROOT);
        boolean dirty = gitHasOutput(root, "status", "--porcelain=v1", "--untracked-files=all");
        return new RepositoryRevision(commit, dirty);
    }

    private static Path requireRepositoryRoot(Path repository) throws IOException, InterruptedException {
        Path root = Objects.requireNonNull(repository, "repository must not be null")
                .toAbsolutePath()
                .normalize()
                .toRealPath();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("repository must be an existing directory");
        }
        Path discoveredRoot = Path.of(runGit(root, "rev-parse", "--show-toplevel"))
                .toAbsolutePath()
                .normalize()
                .toRealPath();
        if (!discoveredRoot.equals(root)) {
            throw new IllegalArgumentException("path must be the root of an independent Git repository");
        }
        return root;
    }

    public void requireClean(String label) {
        if (dirty) {
            throw new IllegalArgumentException(label + " worktree must be clean before Execute");
        }
    }

    public void requireCommit(String expected, String label) {
        if (expected == null || !expected.matches("[0-9a-f]{40}") || !commit.equals(expected)) {
            throw new IllegalArgumentException(label + " commit must exactly match the checked-out HEAD");
        }
    }

    public void requireCompatibleBaseline(Path repository, String baseline, String label)
            throws IOException, InterruptedException {
        if (baseline == null || !baseline.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(label + " baseline must be a full lowercase Git commit");
        }
        Path root = requireRepositoryRoot(repository);
        if (!equals(inspectRoot(root))) {
            throw new IllegalArgumentException(label + " revision no longer matches the checked-out repository");
        }
        int exitCode = runGitExitCode(root, "merge-base", "--is-ancestor", baseline, commit);
        if (exitCode == 1) {
            throw new IllegalArgumentException(label + " baseline must be an ancestor of the checked-out HEAD");
        }
        if (exitCode != 0) {
            throw new IOException("Git repository compatibility inspection failed");
        }
    }

    public void requireUnchanged(RepositoryRevision current, String label) {
        Objects.requireNonNull(current, "current revision must not be null");
        if (!equals(current)) {
            throw new IllegalArgumentException(label + " revision changed during Execute");
        }
    }

    private static String runGit(Path root, String... arguments) throws IOException, InterruptedException {
        Process process = startGit(root, arguments);
        byte[] output;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var outputReader = executor.submit(() -> process.getInputStream().readAllBytes());
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Git repository inspection timed out");
            }
            output = outputReader.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException exception) {
            process.destroyForcibly();
            throw new IOException("Git repository output could not be read", exception);
        }
        if (process.exitValue() != 0) {
            throw new IOException("Git repository inspection failed");
        }
        return new String(output, StandardCharsets.UTF_8).trim();
    }

    private static boolean gitHasOutput(Path root, String... arguments) throws IOException, InterruptedException {
        Process process = startGit(root, arguments);
        boolean hasOutput;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var outputReader = executor.submit(() -> {
                boolean found = false;
                byte[] buffer = new byte[8192];
                int count;
                while ((count = process.getInputStream().read(buffer)) >= 0) {
                    found |= count > 0;
                }
                return found;
            });
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Git repository inspection timed out");
            }
            hasOutput = outputReader.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException exception) {
            process.destroyForcibly();
            throw new IOException("Git repository output could not be read", exception);
        }
        if (process.exitValue() != 0) {
            throw new IOException("Git repository inspection failed");
        }
        return hasOutput;
    }

    private static int runGitExitCode(Path root, String... arguments) throws IOException, InterruptedException {
        Process process = startGit(root, arguments);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var outputReader = executor.submit(() -> process.getInputStream().readAllBytes());
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Git repository inspection timed out");
            }
            outputReader.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException exception) {
            process.destroyForcibly();
            throw new IOException("Git repository output could not be read", exception);
        }
        return process.exitValue();
    }

    private static Process startGit(Path root, String... arguments) throws IOException {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(java.util.List.of(arguments));
        return new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
