package io.haifa.agent.testing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ProviderNeutralTestAssetNamingTest {
    private static final List<String> VENDOR_TOKENS =
            List.of("deepseek", "bailian", "dashscope", "qwen", "aliyun", "openai", "anthropic", "claude", "gemini");

    @Test
    void suiteAndSharedFixtureImplementationRemainProviderNeutral() throws Exception {
        Path repository = findRepositoryRoot();
        List<Path> roots = List.of(
                repository.resolve("haifa-agent-testing/haifa-agent-testkit/src/main"),
                repository.resolve("haifa-agent-testing/haifa-agent-test-harness/src/main"),
                repository.resolve("haifa-agent-testing/haifa-agent-test-fixtures/src/main"),
                repository.resolve("haifa-agent-testing/haifa-agent-e2e-tests/src/test"));
        List<String> violations = new ArrayList<>();
        for (Path root : roots) {
            try (var paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    String relative = repository.relativize(file).toString();
                    String content =
                            Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    for (String token : VENDOR_TOKENS) {
                        if (relative.toLowerCase(Locale.ROOT).contains(token) || content.contains(token)) {
                            violations.add(relative + " contains vendor token: " + token);
                        }
                    }
                }
            }
        }
        assertTrue(
                violations.isEmpty(),
                () -> "test suites and shared fixtures must use injected provider configuration:\n"
                        + String.join("\n", violations));
    }

    private static Path findRepositoryRoot() {
        Path current =
                Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".mvn")) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate repository root from Maven basedir");
    }
}
