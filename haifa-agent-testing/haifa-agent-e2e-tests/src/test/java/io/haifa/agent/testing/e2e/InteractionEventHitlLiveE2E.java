package io.haifa.agent.testing.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
@Tag("e2e")
class InteractionEventHitlLiveE2E {
    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(12);
    private static final List<String> SECRET_NAMES =
            List.of("DEEPSEEK_API_KEY", "ALIYUN_IQS_API_KEY", "HAIFA_CONTINUATION_KEY");
    private static final List<Pattern> FORBIDDEN_PATTERNS =
            List.of(Pattern.compile("Bearer\\s+[A-Za-z0-9._-]+"), Pattern.compile("sk-[A-Za-z0-9_-]+"));

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
    void completesInteractionEventAndHitlRoundTrip() throws Exception {
        Path caseRoot = newCaseRoot();
        Path workspace = Files.createDirectory(caseRoot.resolve("workspace"));
        Path data = Files.createDirectory(caseRoot.resolve("data"));
        Path transcripts = Files.createDirectory(caseRoot.resolve("transcripts"));
        Path database = data.resolve("runtime.db");
        Path configuration = configRoot.resolve("environments/cli/interaction-live.yaml");
        if (!Files.isRegularFile(configuration)) {
            throw new IllegalStateException("interaction live configuration is unavailable");
        }
        Map<String, String> persistenceEnvironment = Map.of(
                "HAIFA_SQLITE_DATABASE_PATH", database.toString(), "HAIFA_TRANSCRIPT_ROOT", transcripts.toString());

        Set<String> beforeBaseline = runIds(database);
        CliResult baseline = runCli(
                "l11-01-model-baseline",
                caseRoot,
                workspace,
                configuration,
                "不要调用任何工具。只回答：INTERACTION_MODEL_LIVE_OK。",
                List.of(),
                persistenceEnvironment);
        assertSuccessful(baseline);
        assertThat(baseline.standardOutput()).contains("INTERACTION_MODEL_LIVE_OK");
        String baselineRun = onlyNewRun(database, beforeBaseline);
        assertRun(database, baselineRun, 1, 0);
        assertThat(toolCalls(database, baselineRun)).isEmpty();
        assertThat(count(database, "SELECT COUNT(*) FROM interaction_request WHERE run_id = ?", baselineRun))
                .isZero();

        Set<String> beforeReject = runIds(database);
        CliResult rejected = runCli(
                "l11-02-reject-search",
                caseRoot,
                workspace,
                configuration,
                "必须调用 web_search 搜索今天的 Java Agent Runtime 公开资料，只允许 query 和 maxResults=3；" + "不能仅凭已有知识回答。",
                List.of(new ApprovalPromptDriver.Decision("web_search", "n")),
                persistenceEnvironment);
        assertSuccessful(rejected);
        assertThat(rejected.trace()).doesNotContain("\"operation\":\"tool.execute\"");
        String rejectedRun = onlyNewRun(database, beforeReject);
        assertRun(database, rejectedRun, 1, 0);
        assertThat(toolCalls(database, rejectedRun)).containsExactly(new ToolCallRecord("web.search", "DENIED"));
        assertInteractionRecords(database, rejectedRun, List.of("REJECT"));
        assertJournal(database, rejectedRun, 1);

        Set<String> beforeApproved = runIds(database);
        CliResult approved = runCli(
                "l11-03-approve-search-fetch",
                caseRoot,
                workspace,
                configuration,
                "这是 11 系列 HITL 综合测试。第一步必须调用 web_search 搜索 Alibaba Cloud IQS "
                        + "ReadPageBasic 官方文档，只使用 query 和 maxResults=3；第二步必须从搜索结果选择一个"
                        + "公开 HTTPS 官方文档 URL，并调用 web_fetch，preferredFormat=markdown、"
                        + "maxCharacters=5000；最后列出搜索结果标题和 URL、实际 fetch 的 finalUrl、"
                        + "contentSha256 与简短摘要。不能跳过任何 Tool Call。",
                List.of(
                        new ApprovalPromptDriver.Decision("web_search", "y"),
                        new ApprovalPromptDriver.Decision("web_fetch", "y")),
                persistenceEnvironment);
        assertSuccessful(approved);
        assertThat(approved.trace())
                .contains("\"operation\":\"tool.execute\"")
                .contains("\"toolName\":\"web.search\"")
                .contains("\"toolName\":\"web.fetch\"");
        String approvedRun = onlyNewRun(database, beforeApproved);
        assertRun(database, approvedRun, 3, 2);
        assertThat(toolCalls(database, approvedRun))
                .containsExactly(
                        new ToolCallRecord("web.search", "COMPLETED"), new ToolCallRecord("web.fetch", "COMPLETED"));
        assertInteractionRecords(database, approvedRun, List.of("APPROVE", "APPROVE"));
        assertJournal(database, approvedRun, 2);
        assertThat(pendingOutbox(database)).isZero();

        Map<Path, String> transcriptHashes = transcriptHashes(transcripts);
        assertThat(transcriptHashes).isNotEmpty();
        Set<String> beforeSecondProcess = runIds(database);
        CliResult secondProcess = runCli(
                "l11-04-second-process",
                caseRoot,
                workspace,
                configuration,
                "不要调用任何工具。只回答：INTERACTION_SECOND_PROCESS_OK。",
                List.of(),
                persistenceEnvironment);
        assertSuccessful(secondProcess);
        assertThat(secondProcess.standardOutput()).contains("INTERACTION_SECOND_PROCESS_OK");
        String secondProcessRun = onlyNewRun(database, beforeSecondProcess);
        assertRun(database, secondProcessRun, 1, 0);
        assertThat(runIds(database)).contains(baselineRun, rejectedRun, approvedRun, secondProcessRun);
        transcriptHashes.forEach((path, hash) -> assertThat(sha256(path))
                .as("existing transcript %s", path.getFileName())
                .isEqualTo(hash));
        assertThat(pendingOutbox(database)).isZero();

        assertNoSensitiveOutput(caseRoot);
    }

    private static CliResult runCli(
            String phase,
            Path caseRoot,
            Path workspace,
            Path configuration,
            String task,
            List<ApprovalPromptDriver.Decision> decisions,
            Map<String, String> environment)
            throws Exception {
        Path trace = caseRoot.resolve(phase + "-trace.jsonl");
        Path standardOutput = caseRoot.resolve(phase + "-stdout.log");
        Path standardError = caseRoot.resolve(phase + "-stderr.log");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-jar");
        command.add(cliJar.toString());
        command.add("--workspace");
        command.add(workspace.toString());
        command.add("--config");
        command.add(configuration.toString());
        command.add("--approval");
        command.add("ask");
        command.add("--timeout");
        command.add("PT10M");
        command.add("--trace");
        command.add("jsonl");
        command.add("--trace-file");
        command.add(trace.toString());
        command.add("--verbose");
        command.add("-m");
        command.add(task);

        ProcessBuilder builder = new ProcessBuilder(command).directory(projectRoot.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        var approvalDriver = new ApprovalPromptDriver(decisions);
        String stderr;
        Throwable stdoutFailure = null;
        boolean completed;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Void> stdout = executor.submit(() -> {
                interact(process, approvalDriver);
                return null;
            });
            Future<String> stderrReader =
                    executor.submit(() -> new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            completed = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            }
            try {
                stdout.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                stdoutFailure = exception.getCause();
            }
            stderr = stderrReader.get(10, TimeUnit.SECONDS);
        }
        String stdout = approvalDriver.output();
        Files.writeString(standardOutput, stdout, StandardCharsets.UTF_8);
        Files.writeString(standardError, stderr, StandardCharsets.UTF_8);
        if (stdoutFailure != null) {
            throw new AssertionError("interactive CLI driver failed during " + phase, stdoutFailure);
        }
        int exitCode = completed ? process.exitValue() : 124;
        return new CliResult(exitCode, readIfPresent(trace), stdout, stderr, trace, standardOutput, standardError);
    }

    private static void interact(Process process, ApprovalPromptDriver driver) throws Exception {
        try (var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
                var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            int value;
            while ((value = reader.read()) >= 0) {
                driver.accept((char) value).ifPresent(response -> writeResponse(writer, response));
            }
            driver.assertComplete();
        } catch (Exception exception) {
            process.destroyForcibly();
            throw exception;
        }
    }

    private static void writeResponse(OutputStreamWriter writer, String response) {
        try {
            writer.write(response);
            writer.flush();
        } catch (Exception exception) {
            throw new IllegalStateException("approval response could not be written", exception);
        }
    }

    private static void assertSuccessful(CliResult result) {
        assertThat(result.exitCode())
                .as("CLI exit code; inspect %s and %s", result.standardOutputPath(), result.standardErrorPath())
                .isZero();
        assertThat(result.trace()).contains("\"operation\":\"model.invoke\"");
        rejectSecretValues(result.trace());
        rejectSecretValues(result.standardOutput());
        rejectSecretValues(result.standardError());
    }

    private static void assertRun(Path database, String runId, long minimumModelCalls, long expectedToolCalls)
            throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement(
                        """
                        SELECT status, usage_model_calls, usage_tool_calls
                        FROM run
                        WHERE run_id = ?
                        """)) {
            statement.setString(1, runId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("status")).isEqualTo("COMPLETED");
                assertThat(rows.getLong("usage_model_calls")).isGreaterThanOrEqualTo(minimumModelCalls);
                assertThat(rows.getLong("usage_tool_calls")).isEqualTo(expectedToolCalls);
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private static List<ToolCallRecord> toolCalls(Path database, String runId) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement(
                        """
                        SELECT tool_name, status
                        FROM tool_call
                        WHERE run_id = ?
                        ORDER BY requested_at, tool_call_id
                        """)) {
            statement.setString(1, runId);
            try (var rows = statement.executeQuery()) {
                List<ToolCallRecord> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new ToolCallRecord(rows.getString("tool_name"), rows.getString("status")));
                }
                return List.copyOf(result);
            }
        }
    }

    private static void assertInteractionRecords(Path database, String runId, List<String> responses) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement(
                        """
                        SELECT request.state, request.revision, request.approval, request.target_type,
                               response.response_type, response.action, response.expected_revision,
                               response.receipt_status, application.resolution_applied
                        FROM interaction_request request
                        JOIN interaction_response response ON response.request_id = request.request_id
                        JOIN interaction_application application ON application.request_id = request.request_id
                        WHERE request.run_id = ?
                        ORDER BY request.created_at, request.request_id
                        """)) {
            statement.setString(1, runId);
            try (var rows = statement.executeQuery()) {
                List<String> observed = new ArrayList<>();
                while (rows.next()) {
                    observed.add(rows.getString("response_type"));
                    assertThat(rows.getString("state")).isEqualTo("APPLIED");
                    assertThat(rows.getLong("revision")).isEqualTo(2);
                    assertThat(rows.getLong("approval")).isEqualTo(1);
                    assertThat(rows.getString("target_type")).isEqualTo("tool-approval");
                    assertThat(rows.getString("action"))
                            .isEqualTo(rows.getString("response_type").toLowerCase(Locale.ROOT));
                    assertThat(rows.getLong("expected_revision")).isZero();
                    assertThat(rows.getString("receipt_status")).isEqualTo("ACCEPTED");
                    assertThat(rows.getLong("resolution_applied")).isEqualTo(1);
                }
                assertThat(observed).containsExactlyElementsOf(responses);
            }
        }
        assertThat(count(
                        database,
                        "SELECT COUNT(*) FROM interaction_request WHERE run_id = ? AND state = 'PENDING'",
                        runId))
                .isZero();
    }

    private static void assertJournal(Path database, String runId, long expectedApprovals) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement(
                        """
                        SELECT COUNT(*) AS event_count, COUNT(DISTINCT sequence) AS distinct_sequences,
                               MIN(sequence) AS first_sequence, MAX(sequence) AS last_sequence
                        FROM runtime_event
                        WHERE run_id = ?
                        """)) {
            statement.setString(1, runId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                long count = rows.getLong("event_count");
                assertThat(count).isPositive();
                assertThat(rows.getLong("distinct_sequences")).isEqualTo(count);
                assertThat(rows.getLong("first_sequence")).isEqualTo(1);
                assertThat(rows.getLong("last_sequence")).isEqualTo(count);
                assertThat(count(database, "SELECT head_sequence FROM runtime_event_stream WHERE run_id = ?", runId))
                        .isEqualTo(count);
            }
        }
        assertThat(count(
                        database,
                        "SELECT COUNT(*) FROM runtime_event WHERE run_id = ? AND type = 'approval.requested'",
                        runId))
                .isEqualTo(expectedApprovals);
        assertThat(count(
                        database,
                        "SELECT COUNT(*) FROM runtime_event WHERE run_id = ? AND type = 'approval.responded'",
                        runId))
                .isEqualTo(expectedApprovals);
    }

    private static long pendingOutbox(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL")) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private static long count(Path database, String query, String runId) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement(query)) {
            statement.setString(1, runId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static Set<String> runIds(Path database) throws Exception {
        if (!Files.isRegularFile(database)) return Set.of();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT run_id FROM run")) {
            var result = new java.util.LinkedHashSet<String>();
            while (rows.next()) result.add(rows.getString(1));
            return Set.copyOf(result);
        }
    }

    private static String onlyNewRun(Path database, Set<String> previous) throws Exception {
        Set<String> current = runIds(database);
        Set<String> added =
                current.stream().filter(runId -> !previous.contains(runId)).collect(Collectors.toSet());
        assertThat(added).as("one new run should be persisted").hasSize(1);
        return added.iterator().next();
    }

    private static Map<Path, String> transcriptHashes(Path transcripts) throws Exception {
        try (var paths = Files.walk(transcripts)) {
            Map<Path, String> result = new LinkedHashMap<>();
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                result.put(path, sha256(path));
            }
            return Map.copyOf(result);
        }
    }

    private static void assertNoSensitiveOutput(Path caseRoot) throws Exception {
        try (var paths = Files.walk(caseRoot)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(InteractionEventHitlLiveE2E::isInspectableText)
                    .toList()) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                rejectSecretValues(content);
                FORBIDDEN_PATTERNS.forEach(
                        pattern -> assertThat(pattern.matcher(content).find())
                                .as("forbidden secret-shaped value in %s", path)
                                .isFalse());
            }
        }
    }

    private static boolean isInspectableText(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jsonl") || name.endsWith(".log") || name.endsWith(".txt");
    }

    private static void rejectSecretValues(String value) {
        SECRET_NAMES.forEach(name -> {
            String secret = System.getenv(name);
            if (secret != null && !secret.isBlank()) assertThat(value).doesNotContain(secret);
        });
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("file digest could not be calculated", exception);
        }
    }

    private static Path newCaseRoot() throws Exception {
        Files.createDirectories(runRoot);
        return Files.createDirectory(runRoot.resolve("runs-cp-11-" + UUID.randomUUID()));
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

    private record ToolCallRecord(String toolName, String status) {}

    private record CliResult(
            int exitCode,
            String trace,
            String standardOutput,
            String standardError,
            Path tracePath,
            Path standardOutputPath,
            Path standardErrorPath) {}
}
