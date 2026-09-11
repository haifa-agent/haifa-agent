package io.haifa.agent.execution.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.haifa.agent.execution.api.ExecutionStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandSemanticOutcomeInterpreterIT {
    @Test
    void interpretsTheRealGitNoIndexDifferenceExitCode(@TempDir Path temporary) throws Exception {
        assumeTrue(gitAvailable(), "git is not available on PATH");
        Path before = temporary.resolve("before.txt");
        Path after = temporary.resolve("after.txt");
        Files.writeString(before, "before\n", StandardCharsets.UTF_8);
        Files.writeString(after, "after\n", StandardCharsets.UTF_8);

        Process process = new ProcessBuilder("git", "diff", "--no-index", "--", before.toString(), after.toString())
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        int exitCode = process.exitValue();

        assertThat(exitCode).isEqualTo(1);
        CommandSemanticOutcomeInterpreter.Interpretation interpretation = CommandSemanticOutcomeInterpreter.interpret(
                "git diff --no-index -- before after", ExecutionStatus.EXITED, exitCode);
        assertThat(interpretation.outcome()).isEqualTo(CommandSemanticOutcome.SUCCEEDED);
        assertThat(interpretation.reasonCode()).isEqualTo("COMMAND_EXITED");
        assertThat(interpretation.successfulToolResult()).isTrue();
    }

    private static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
