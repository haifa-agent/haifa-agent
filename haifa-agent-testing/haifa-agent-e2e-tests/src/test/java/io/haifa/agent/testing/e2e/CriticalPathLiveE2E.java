package io.haifa.agent.testing.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
@Tag("e2e")
class CriticalPathLiveE2E {
    private static Path projectRoot;
    private static Path configRoot;
    private static Path runRoot;
    private static Path cliJar;

    @BeforeAll
    static void requireSuiteExecution() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("HAIFA_SUITE_EXECUTION")));
        projectRoot = requireDirectory("HAIFA_AGENT_ROOT");
        configRoot = requireDirectory("HAIFA_TEST_CONFIG_ROOT");
        runRoot = requireAbsolutePath("HAIFA_TEST_RUN_ROOT");
        if (runRoot.startsWith(projectRoot) || runRoot.startsWith(configRoot)) {
            throw new IllegalStateException("HAIFA_TEST_RUN_ROOT must be outside both Git repositories");
        }
        cliJar = projectRoot
                .resolve("haifa-agent-applications/haifa-agent-cli/target/haifa-agent-cli-0.1.0-SNAPSHOT.jar")
                .normalize();
        if (!Files.isRegularFile(cliJar)) throw new IllegalStateException("shaded CLI jar is unavailable");
    }

    @Test
    void activatesReviewedSkill() throws Exception {
        Path caseRoot = newCaseRoot("cp-07");
        Path workspace = Files.createDirectory(caseRoot.resolve("workspace"));
        Path generatedConfig = caseRoot.resolve("skill-live.yaml");
        String skillRoot = configRoot.resolve("fixtures/skills").toString().replace('\\', '/');
        String template = Files.readString(configRoot.resolve("environments/cli/skill-live.yaml.template"));
        Files.writeString(generatedConfig, template.replace("__HAIFA_SKILL_ROOT__", skillRoot));

        RunResult result = runCli(
                caseRoot,
                workspace,
                generatedConfig,
                "只调用一次 skill_load 加载 ascii-art。加载成功后不要调用 skill_resource_read 或任何其他工具，"
                        + "立即只输出一个包含 HAIFA AGENT 的简短纯文本 ASCII 图。",
                Map.of());

        assertSuccessful(result);
        assertThat(result.trace())
                .contains("\"operation\":\"tool.execute\"")
                .contains("\"toolName\":\"skill.load\"")
                .contains("\"providerId\":\"haifa-runtime-skill\"");
    }

    @Test
    void searchesAndFetchesPublicWebContent() throws Exception {
        Path caseRoot = newCaseRoot("cp-08");
        Path workspace = Files.createDirectory(caseRoot.resolve("workspace"));
        RunResult result = runCli(
                caseRoot,
                workspace,
                configRoot.resolve("environments/cli/web-live.yaml"),
                "必须先调用 web_search 搜索 Alibaba Cloud IQS ReadPageBasic 官方文档，再从结果选择公开 HTTPS 官方 URL 调用 web_fetch；最后简要回答。",
                Map.of());

        assertSuccessful(result);
        assertThat(result.trace())
                .contains("\"operation\":\"tool.execute\"")
                .contains("\"toolName\":\"web.search\"")
                .contains("\"toolName\":\"web.fetch\"");
    }

    @Test
    void persistsRunToSqliteAndJsonl() throws Exception {
        Path caseRoot = newCaseRoot("cp-10");
        Path workspace = Files.createDirectory(caseRoot.resolve("workspace"));
        Path data = Files.createDirectory(caseRoot.resolve("data"));
        Path transcripts = Files.createDirectory(caseRoot.resolve("transcripts"));
        Path database = data.resolve("runtime.db");
        RunResult result = runCli(
                caseRoot,
                workspace,
                configRoot.resolve("environments/cli/persistence-live.yaml"),
                "请只回答 CP10_OK，不要调用工具。",
                Map.of(
                        "HAIFA_SQLITE_DATABASE_PATH", database.toString(),
                        "HAIFA_TRANSCRIPT_ROOT", transcripts.toString()));

        assertSuccessful(result);
        assertThat(database).isRegularFile();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT status, usage_model_calls FROM run ORDER BY created_at DESC LIMIT 1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("status")).isEqualTo("COMPLETED");
            assertThat(rows.getLong("usage_model_calls")).isGreaterThanOrEqualTo(1);
        }
        try (var files = Files.list(transcripts)) {
            assertThat(files.filter(Files::isRegularFile).anyMatch(path -> fileSize(path) > 0))
                    .isTrue();
        }
    }

    private static RunResult runCli(
            Path caseRoot, Path workspace, Path configuration, String task, Map<String, String> environment)
            throws Exception {
        Path trace = caseRoot.resolve("trace.jsonl");
        Path standardOutput = caseRoot.resolve("stdout.log");
        Path standardError = caseRoot.resolve("stderr.log");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-jar");
        command.add(cliJar.toString());
        command.add("--workspace");
        command.add(workspace.toString());
        command.add("--config");
        command.add(configuration.toString());
        command.add("--approval");
        command.add("auto");
        command.add("--timeout");
        command.add("PT10M");
        command.add("--trace");
        command.add("jsonl");
        command.add("--trace-file");
        command.add(trace.toString());
        command.add("-m");
        command.add(task);

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectOutput(standardOutput.toFile())
                .redirectError(standardError.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        boolean completed = process.waitFor(Duration.ofMinutes(12).toSeconds(), TimeUnit.SECONDS);
        if (!completed) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        }
        int exitCode = completed ? process.exitValue() : 124;
        return new RunResult(
                exitCode, readIfPresent(trace), readIfPresent(standardOutput), readIfPresent(standardError));
    }

    private static void assertSuccessful(RunResult result) {
        assertThat(result.exitCode())
                .as("CLI exit code; inspect repository-external run artifacts")
                .isZero();
        assertThat(result.trace()).contains("\"operation\":\"model.invoke\"");
        rejectSensitiveValue(result.trace());
        rejectSensitiveValue(result.standardOutput());
        rejectSensitiveValue(result.standardError());
    }

    private static void rejectSensitiveValue(String value) {
        for (String secretName : List.of("DEEPSEEK_API_KEY", "ALIYUN_IQS_API_KEY", "HAIFA_CONTINUATION_KEY")) {
            String secret = System.getenv(secretName);
            if (secret != null && !secret.isBlank()) assertThat(value).doesNotContain(secret);
        }
    }

    private static Path newCaseRoot(String caseId) throws Exception {
        Files.createDirectories(runRoot);
        return Files.createDirectory(runRoot.resolve("runs-" + caseId + "-" + UUID.randomUUID()));
    }

    private static Path requireDirectory(String environmentName) {
        Path path = requireAbsolutePath(environmentName);
        if (!Files.isDirectory(path)) throw new IllegalStateException(environmentName + " must be a directory");
        return path;
    }

    private static Path requireAbsolutePath(String environmentName) {
        String value = System.getenv(environmentName);
        if (value == null || value.isBlank()) throw new IllegalStateException(environmentName + " is required");
        Path configured = Path.of(value);
        if (!configured.isAbsolute()) throw new IllegalStateException(environmentName + " must be absolute");
        return configured.normalize();
    }

    private static Path javaExecutable() {
        String executable =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static String readIfPresent(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (Exception exception) {
            throw new IllegalStateException("test evidence could not be read", exception);
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception exception) {
            return 0;
        }
    }

    private record RunResult(int exitCode, String trace, String standardOutput, String standardError) {}
}
