package io.haifa.agent.application.project.product.coding.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingGitDeliveryIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void deliversExactRootScopeToBareRemoteAndPreservesIndependentNestedRepository() throws Exception {
        Path remote = directory.resolve("origin.git");
        Path root = directory.resolve("root");
        Path docs = root.resolve("docs");
        git(directory, "init", "--bare", remote.toString()).requireSuccess();
        git(directory, "init", root.toString()).requireSuccess();
        configure(root);
        git(root, "checkout", "-b", "feat-delivery").requireSuccess();
        Files.writeString(root.resolve(".gitignore"), "docs/\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("README.md"), "initial\n", StandardCharsets.UTF_8);
        git(root, "add", ".gitignore", "README.md").requireSuccess();
        git(root, "commit", "-m", "chore: initialize fixture").requireSuccess();
        git(root, "remote", "add", "origin", remote.toString()).requireSuccess();

        Files.createDirectories(docs);
        git(root, "init", docs.toString()).requireSuccess();
        configure(docs);
        Files.writeString(docs.resolve("README.md"), "docs initial\n", StandardCharsets.UTF_8);
        git(docs, "add", "README.md").requireSuccess();
        git(docs, "commit", "-m", "docs: initialize fixture").requireSuccess();
        Files.writeString(docs.resolve("README.md"), "docs changed\n", StandardCharsets.UTF_8);

        Files.writeString(root.resolve("README.md"), "root changed\n", StandardCharsets.UTF_8);
        assertThat(git(root, "diff", "--", "README.md").output()).contains("root changed");
        git(root, "add", "README.md").requireSuccess();
        assertThat(git(root, "diff", "--cached", "--", "README.md").output()).contains("root changed");
        git(root, "commit", "-m", "feat: deliver fixture change").requireSuccess();
        String deliveredHead =
                git(root, "rev-parse", "HEAD").requireSuccess().output().trim();
        git(root, "push", "origin", "feat-delivery").requireSuccess();
        assertThat(git(root, "ls-remote", "--heads", "origin", "refs/heads/feat-delivery")
                        .requireSuccess()
                        .output())
                .startsWith(deliveredHead);
        assertThat(git(root, "status", "--short").requireSuccess().output()).isBlank();
        assertThat(git(docs, "status", "--short").requireSuccess().output()).contains("M README.md");

        Path peer = directory.resolve("peer");
        git(directory, "clone", remote.toString(), peer.toString()).requireSuccess();
        configure(peer);
        git(peer, "checkout", "-b", "feat-delivery", "origin/feat-delivery").requireSuccess();
        Files.writeString(peer.resolve("peer.txt"), "peer\n", StandardCharsets.UTF_8);
        git(peer, "add", "peer.txt").requireSuccess();
        git(peer, "commit", "-m", "feat: advance remote fixture").requireSuccess();
        git(peer, "push", "origin", "feat-delivery").requireSuccess();
        String remoteHead =
                git(peer, "rev-parse", "HEAD").requireSuccess().output().trim();

        Files.writeString(root.resolve("local.txt"), "local\n", StandardCharsets.UTF_8);
        git(root, "add", "local.txt").requireSuccess();
        git(root, "commit", "-m", "feat: diverge local fixture").requireSuccess();
        CommandResult rejected = git(root, "push", "origin", "feat-delivery");
        assertThat(rejected.exitCode()).isNotZero();
        assertThat(git(root, "ls-remote", "--heads", "origin", "refs/heads/feat-delivery")
                        .requireSuccess()
                        .output())
                .startsWith(remoteHead);
        assertThat(git(root, "rev-parse", "HEAD").requireSuccess().output().trim())
                .isNotEqualTo(remoteHead);
    }

    private static void configure(Path repository) throws Exception {
        git(repository, "config", "user.name", "Haifa Test").requireSuccess();
        git(repository, "config", "user.email", "haifa-test@example.invalid").requireSuccess();
    }

    private static CommandResult git(Path workdir, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(workdir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new CommandResult(exitCode, output);
    }

    private record CommandResult(int exitCode, String output) {
        private CommandResult requireSuccess() throws IOException {
            if (exitCode != 0) throw new IOException("git fixture command failed: " + output);
            return this;
        }
    }
}
