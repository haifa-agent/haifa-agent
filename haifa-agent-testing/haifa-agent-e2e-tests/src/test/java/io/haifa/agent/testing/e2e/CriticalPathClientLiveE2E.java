package io.haifa.agent.testing.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.cli.StandaloneCodingAgent;
import io.haifa.agent.cli.StandaloneCodingAgentMetadata;
import io.haifa.agent.cli.StandaloneCodingAgents;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Product-semantic Critical Path cases executed through the standard Coding Agent client. */
@Tag("live")
@Tag("e2e")
@Tag("coding-product")
@Execution(ExecutionMode.SAME_THREAD)
class CriticalPathClientLiveE2E {
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
    void completesAgentBaselineTurn() throws Exception {
        ClientResult result = runClient("cp-01", "只回答 CP01_OK，不要调用工具。");

        assertCompleted(result);
        assertThat(modelCalls(result)).anySatisfy(model -> {
            assertThat(model.providerId()).isEqualTo(result.metadata().providerId());
            assertThat(model.modelId()).isEqualTo(result.metadata().modelId());
            assertThat(model.inputTokens()).isPositive();
            assertThat(model.outputTokens()).isPositive();
        });
    }

    @Test
    void activatesReviewedSkill() throws Exception {
        ClientResult result =
                runClient("cp-07", "只调用一次 skill_load 加载 ascii-art。加载成功后不要调用其他工具，立即只输出一个包含 HAIFA AGENT 的简短纯文本 ASCII 图。");

        assertCompleted(result);
        assertSucceededTool(result, "skill.load");
    }

    @Test
    void searchesAndFetchesPublicWebContent() throws Exception {
        ClientResult result = runClient(
                "cp-08",
                "必须先调用 web_search 搜索 Alibaba Cloud IQS ReadPageBasic 官方文档，再从结果选择公开 HTTPS 官方 URL 调用 web_fetch；最后简要回答。");

        assertCompleted(result);
        assertSucceededTool(result, "web.search");
        assertSucceededTool(result, "web.fetch");
    }

    @Test
    void discoversAndCallsUtilityMcp() throws Exception {
        ClientResult result = runClient("cp-09", "必须调用 mcp.utility.calculate 计算 7*6；只根据工具结果回答。禁止自行心算替代工具调用。");

        assertCompleted(result);
        assertSucceededTool(result, "mcp.utility.calculate");
    }

    @Test
    void persistsRunToSqliteAndJsonl() throws Exception {
        Path caseRoot = newCaseRoot("cp-10");
        Path workspace = Files.createDirectory(caseRoot.resolve("workspace"));
        Path database = requireAbsolutePath("HAIFA_SQLITE_DATABASE_PATH");
        Path transcripts = requireAbsolutePath("HAIFA_TRANSCRIPT_ROOT");
        Files.createDirectories(database.getParent());
        Files.createDirectories(transcripts);

        ClientResult result = runClient(workspace, "只回答 CP10_OK，不要调用工具。");

        assertCompleted(result);
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
            assertThat(files.filter(Files::isRegularFile).anyMatch(CriticalPathClientLiveE2E::hasContent))
                    .isTrue();
        }
    }

    private static ClientResult runClient(String caseId, String task) throws Exception {
        Path caseRoot = newCaseRoot(caseId);
        return runClient(Files.createDirectory(caseRoot.resolve("workspace")), task);
    }

    private static ClientResult runClient(Path workspace, String task) throws Exception {
        try (StandaloneCodingAgent agent = StandaloneCodingAgents.open(workspace, agentConfiguration)) {
            return execute(agent.client(), agent.projectId(), agent.metadata(), task);
        }
    }

    private static ClientResult execute(
            CodingSessionClient client, ProjectId projectId, StandaloneCodingAgentMetadata metadata, String task)
            throws Exception {
        var created = client.create(projectId, task, "critical-path-" + UUID.randomUUID());
        var sessionId = created.summary().sessionId();
        AgentRunSnapshot snapshot = created.activeRun().orElseThrow();
        Instant deadline = Instant.now().plus(Duration.ofMinutes(12));
        while (!snapshot.status().isTerminal() && Instant.now().isBefore(deadline)) {
            var pending = client.pendingInteraction(snapshot.runId());
            if (pending.isPresent()) {
                var interaction = pending.orElseThrow();
                client.respond(interaction, InteractionAction.APPROVE, "critical-path-approve-" + UUID.randomUUID());
            }
            Thread.sleep(25);
            snapshot = client.findRun(snapshot.runId()).orElseThrow();
        }
        if (!snapshot.status().isTerminal()) {
            client.cancel(sessionId, "critical-path-timeout-" + UUID.randomUUID());
            throw new AssertionError("Critical Path client execution timed out");
        }
        return new ClientResult(snapshot, readAllEvents(client, snapshot), metadata);
    }

    private static List<AgentRunEvent> readAllEvents(CodingSessionClient client, AgentRunSnapshot snapshot) {
        java.util.ArrayList<AgentRunEvent> events = new java.util.ArrayList<>();
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
        assertThat(result.snapshot().status())
                .as("safe run failure: %s", safeFailure(result.snapshot()))
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(modelCalls(result)).isNotEmpty();
    }

    private static String safeFailure(AgentRunSnapshot snapshot) {
        return snapshot.error()
                .map(error -> "code="
                        + error.code().wireCode()
                        + ", category="
                        + error.category()
                        + ", retryability="
                        + error.retryability()
                        + ", details="
                        + error.details()
                        + ", diagnosticId="
                        + error.optionalDiagnosticId().orElse("none"))
                .orElse("none");
    }

    private static List<RunEventPayloads.ModelLifecycle> modelCalls(ClientResult result) {
        return result.events().stream()
                .map(AgentRunEvent::payload)
                .filter(RunEventPayloads.ModelLifecycle.class::isInstance)
                .map(RunEventPayloads.ModelLifecycle.class::cast)
                .filter(model -> model.status().equals("SUCCEEDED"))
                .toList();
    }

    private static void assertSucceededTool(ClientResult result, String toolName) {
        assertThat(result.events().stream()
                        .map(AgentRunEvent::payload)
                        .filter(RunEventPayloads.ToolLifecycle.class::isInstance)
                        .map(RunEventPayloads.ToolLifecycle.class::cast)
                        .filter(tool -> tool.status().equals("SUCCEEDED"))
                        .map(RunEventPayloads.ToolLifecycle::displayName))
                .contains(toolName);
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

    private static boolean hasContent(Path path) {
        try {
            return Files.size(path) > 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private record ClientResult(
            AgentRunSnapshot snapshot, List<AgentRunEvent> events, StandaloneCodingAgentMetadata metadata) {
        private ClientResult {
            events = List.copyOf(events);
        }
    }
}
