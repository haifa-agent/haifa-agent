package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("java.util.logging.root")
class CliFileLoggingTest {
    @TempDir
    Path temp;

    @Test
    void writesBoundedSafeLogsToTheConfiguredDirectory() throws Exception {
        Path logs = temp.resolve("logs");
        try (CliFileLogging logging = CliFileLogging.open(Map.of("HAIFA_LOG_DIR", logs.toString()))) {
            Logger.getLogger("io.haifa.agent.test").info("SAFE_TEST_EVENT");
            logging.logUncaught(Thread.currentThread(), new NoClassDefFoundError("must-not-be-logged"));
            logging.completed(1);
        }

        String content;
        try (var files = Files.list(logs)) {
            content = Files.readString(
                    files.filter(path -> path.getFileName().toString().endsWith(".log"))
                            .findFirst()
                            .orElseThrow());
        }
        assertThat(content)
                .contains("CLI_START", "SAFE_TEST_EVENT", "CLI_UNCAUGHT", "NoClassDefFoundError", "CLI_EXIT code=1")
                .doesNotContain("must-not-be-logged");
    }

    @Test
    void defaultsToThePackagedCodingAgentLogDirectory() {
        assertThat(CliFileLogging.logDirectory(Map.of()))
                .isEqualTo(Path.of(System.getProperty("user.home"), ".haifa-agent", "coding", "logs")
                        .toAbsolutePath()
                        .normalize());
    }
}
