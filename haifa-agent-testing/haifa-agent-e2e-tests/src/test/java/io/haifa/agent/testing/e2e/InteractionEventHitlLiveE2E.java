package io.haifa.agent.testing.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.cli.StandaloneCodingAgent;
import io.haifa.agent.cli.StandaloneCodingAgents;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.RunEventCursor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Tag("live")
@Tag("e2e")
@Tag("coding-product")
@Execution(ExecutionMode.SAME_THREAD)
class InteractionEventHitlLiveE2E {
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(12);
    private static Path projectRoot;
    private static Path configRoot;
    private static Path runRoot;
    private static Path agentConfiguration;

    @BeforeAll
    static void requireSuiteExecution() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("HAIFA_SUITE_EXECUTION")));
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("HAIFA_CODING_CLIENT_LIVE_TEST")));
        projectRoot = requireDirectory("HAIFA_AGENT_ROOT");
        configRoot = requireDirectory("HAIFA_TEST_CONFIG_ROOT");
        runRoot = requireAbsolutePath("HAIFA_TEST_RUN_ROOT");
        agentConfiguration = requireFile("HAIFA_TEST_AGENT_CONFIG");
        if (runRoot.startsWith(projectRoot) || runRoot.startsWith(configRoot)) {
            throw new IllegalStateException("HAIFA_TEST_RUN_ROOT must be outside both Git repositories");
        }
    }

    @Test
    void completesInteractionEventAndHitlRoundTrip() throws Exception {
        Path caseRoot = newCaseRoot();
        Path workspace = Files.createDirectory(caseRoot.resolve("workspace"));
        Path database = requireAbsolutePath("HAIFA_SQLITE_DATABASE_PATH");
        Path transcripts = requireAbsolutePath("HAIFA_TRANSCRIPT_ROOT");
        Files.createDirectories(database.getParent());
        Files.createDirectories(transcripts);

        Set<String> beforeBaseline = runIds(database);
        ClientResult baseline = runClient(workspace, "不要调用任何工具。只回答：INTERACTION_MODEL_LIVE_OK。", List.of());
        assertCompleted(baseline);
        String baselineRun = onlyNewRun(database, beforeBaseline);
        assertRun(database, baselineRun, 1, 0);
        assertThat(toolCalls(database, baselineRun)).isEmpty();
        assertThat(count(database, "SELECT COUNT(*) FROM interaction_request WHERE run_id = ?", baselineRun))
                .isZero();

        Set<String> beforeReject = runIds(database);
        ClientResult rejected = runClient(
                workspace,
                "只允许调用一次 web_search 搜索今天的 Java Agent Runtime 公开资料，参数只允许 query 和 maxResults=3；"
                        + "如果该调用被拒绝，禁止重试或调用其他工具，只回答 SEARCH_REJECTED_OK。",
                List.of(InteractionAction.REJECT));
        assertCompleted(rejected);
        assertThat(rejected.events()).noneMatch(event -> event.eventType().equals("tool.call.succeeded"));
        String rejectedRun = onlyNewRun(database, beforeReject);
        assertRun(database, rejectedRun, 1, 0);
        assertThat(toolCalls(database, rejectedRun)).containsExactly(new ToolCallRecord("web_search", "DENIED"));
        assertInteractionRecords(database, rejectedRun, List.of("REJECT"));
        assertJournal(database, rejectedRun, 1);

        Set<String> beforeApproved = runIds(database);
        ClientResult approved = runClient(
                workspace,
                "这是 11 系列 HITL 综合测试。第一步必须调用 web_search 搜索 Alibaba Cloud IQS ReadPageBasic 官方文档，"
                        + "只使用 query 和 maxResults=3；第二步必须从搜索结果选择公开 HTTPS 官方文档 URL，并调用 web_fetch，"
                        + "preferredFormat=markdown、maxCharacters=5000；最后简要回答。不能跳过任何 Tool Call。",
                List.of(InteractionAction.APPROVE, InteractionAction.APPROVE));
        assertCompleted(approved);
        assertThat(approved.events()).extracting(AgentRunEvent::eventType).contains("tool.call.succeeded");
        String approvedRun = onlyNewRun(database, beforeApproved);
        assertRun(database, approvedRun, 3, 2);
        assertThat(toolCalls(database, approvedRun))
                .containsExactly(
                        new ToolCallRecord("web_search", "COMPLETED"), new ToolCallRecord("web_fetch", "COMPLETED"));
        assertInteractionRecords(database, approvedRun, List.of("APPROVE", "APPROVE"));
        assertJournal(database, approvedRun, 2);
        assertThat(pendingOutbox(database)).isZero();

        Map<Path, String> transcriptHashes = transcriptHashes(transcripts);
        assertThat(transcriptHashes).isNotEmpty();
        Set<String> beforeSecondProcess = runIds(database);
        runClientProcess(caseRoot, workspace, "不要调用任何工具。只回答：INTERACTION_SECOND_PROCESS_OK。");
        String secondProcessRun = onlyNewRun(database, beforeSecondProcess);
        assertRun(database, secondProcessRun, 1, 0);
        assertThat(runIds(database)).contains(baselineRun, rejectedRun, approvedRun, secondProcessRun);
        transcriptHashes.forEach((path, hash) -> assertThat(sha256(path))
                .as("existing transcript %s", path.getFileName())
                .isEqualTo(hash));
        assertThat(pendingOutbox(database)).isZero();
    }

    private static ClientResult runClient(Path workspace, String task, List<InteractionAction> actions)
            throws Exception {
        try (StandaloneCodingAgent agent = StandaloneCodingAgents.open(workspace, agentConfiguration)) {
            return execute(agent.client(), agent.projectId(), task, actions);
        }
    }

    private static void runClientProcess(Path caseRoot, Path workspace, String task) throws Exception {
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path standardOutput = caseRoot.resolve("second-process-stdout.log");
        Path standardError = caseRoot.resolve("second-process-stderr.log");
        Process process = new ProcessBuilder(
                        javaExecutable(),
                        "-cp",
                        classPath,
                        CodingSessionClientProcessMain.class.getName(),
                        workspace.toString(),
                        agentConfiguration.toString(),
                        task)
                .directory(projectRoot.toFile())
                .redirectOutput(standardOutput.toFile())
                .redirectError(standardError.toFile())
                .start();
        boolean finished = process.waitFor(RUN_TIMEOUT.plusSeconds(30).toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
        assertThat(finished).as("second Coding Agent client JVM completed").isTrue();
        assertThat(process.exitValue())
                .as("second Coding Agent client JVM stderr: %s", bounded(standardError))
                .isZero();
    }

    private static String javaExecutable() {
        String executable =
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static String bounded(Path path) throws Exception {
        if (!Files.isRegularFile(path)) return "";
        String content = Files.readString(path);
        return content.length() <= 2_000 ? content : content.substring(0, 2_000);
    }

    private static ClientResult execute(
            CodingSessionClient client, ProjectId projectId, String task, List<InteractionAction> configuredActions)
            throws Exception {
        ArrayList<InteractionAction> actions = new ArrayList<>(configuredActions);
        var created = client.create(projectId, task, "cp-11-" + UUID.randomUUID());
        var sessionId = created.summary().sessionId();
        AgentRunSnapshot snapshot = created.activeRun().orElseThrow();
        Instant deadline = Instant.now().plus(RUN_TIMEOUT);
        while (!snapshot.status().isTerminal() && Instant.now().isBefore(deadline)) {
            var pending = client.pendingInteraction(snapshot.runId());
            if (pending.isPresent()) {
                if (actions.isEmpty()) throw new AssertionError("unexpected Critical Path interaction");
                client.respond(pending.orElseThrow(), actions.removeFirst(), "cp-11-interaction-" + UUID.randomUUID());
            }
            Thread.sleep(25);
            snapshot = client.open(sessionId).activeRun().orElseThrow();
        }
        if (!snapshot.status().isTerminal()) {
            client.cancel(sessionId, "cp-11-timeout-" + UUID.randomUUID());
            throw new AssertionError("CP-11 client execution timed out");
        }
        assertThat(actions)
                .as("all reviewed interaction decisions were consumed")
                .isEmpty();
        return new ClientResult(snapshot, readAllEvents(client, snapshot));
    }

    private static List<AgentRunEvent> readAllEvents(CodingSessionClient client, AgentRunSnapshot snapshot) {
        ArrayList<AgentRunEvent> events = new ArrayList<>();
        RunEventCursor cursor = RunEventCursor.beforeFirst(snapshot.runId());
        boolean more;
        do {
            var page = client.events(snapshot.runId(), cursor, 100);
            events.addAll(page.items());
            cursor = page.nextCursor();
            more = page.hasMore();
        } while (more);
        return List.copyOf(events);
    }

    private static void assertCompleted(ClientResult result) {
        assertThat(result.snapshot().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.events()).extracting(AgentRunEvent::eventType).contains("model.call.succeeded");
    }

    private static void assertRun(Path database, String runId, long minimumModelCalls, long expectedToolCalls)
            throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement(
                        "SELECT status, usage_model_calls, usage_tool_calls FROM run WHERE run_id = ?")) {
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
                        "SELECT tool_name, status FROM tool_call WHERE run_id = ? ORDER BY requested_at, tool_call_id")) {
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
                        FROM runtime_event WHERE run_id = ?
                        """)) {
            statement.setString(1, runId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                long events = rows.getLong("event_count");
                assertThat(events).isPositive();
                assertThat(rows.getLong("distinct_sequences")).isEqualTo(events);
                assertThat(rows.getLong("first_sequence")).isEqualTo(1);
                assertThat(rows.getLong("last_sequence")).isEqualTo(events);
                assertThat(count(database, "SELECT head_sequence FROM runtime_event_stream WHERE run_id = ?", runId))
                        .isEqualTo(events);
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
        Set<String> added = runIds(database).stream()
                .filter(runId -> !previous.contains(runId))
                .collect(Collectors.toSet());
        assertThat(added).as("one new run should be persisted").hasSize(1);
        return added.iterator().next();
    }

    private static Map<Path, String> transcriptHashes(Path transcripts) throws Exception {
        try (var paths = Files.walk(transcripts)) {
            Map<Path, String> result = new LinkedHashMap<>();
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) result.put(path, sha256(path));
            return Map.copyOf(result);
        }
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

    private static Path requireFile(String environmentName) {
        Path path = requireAbsolutePath(environmentName);
        if (!Files.isRegularFile(path)) throw new IllegalStateException(environmentName + " must be a file");
        return path;
    }

    private static Path requireAbsolutePath(String environmentName) {
        String value = System.getenv(environmentName);
        if (value == null || value.isBlank()) throw new IllegalStateException(environmentName + " is required");
        Path configured = Path.of(value);
        if (!configured.isAbsolute()) throw new IllegalStateException(environmentName + " must be absolute");
        return configured.normalize();
    }

    private record ToolCallRecord(String toolName, String status) {}

    private record ClientResult(AgentRunSnapshot snapshot, List<AgentRunEvent> events) {
        private ClientResult {
            events = List.copyOf(events);
        }
    }
}
