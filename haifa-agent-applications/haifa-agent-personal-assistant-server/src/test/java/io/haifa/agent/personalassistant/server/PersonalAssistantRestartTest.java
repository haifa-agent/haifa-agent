package io.haifa.agent.personalassistant.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.application.mission.MissionApplicationService;
import io.haifa.agent.personalassistant.application.mission.MissionConstraints;
import io.haifa.agent.personalassistant.application.mission.MissionMode;
import io.haifa.agent.personalassistant.application.mission.MissionSnapshot;
import io.haifa.agent.personalassistant.application.mission.MissionState;
import io.haifa.agent.personalassistant.application.mission.ResearchBrief;
import io.haifa.agent.personalassistant.server.admin.PersonalAdminQueryService;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.agent.store.sqlite.SqliteStoreFoundation;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class PersonalAssistantRestartTest {
    @Test
    void deepResearchMissionPublishesStableArtifactsAndFinalConversationMessageAcrossRestart() throws Exception {
        Path data = Files.createTempDirectory("haifa-personal-deep-research-");
        String conversationId;
        String missionId;
        MissionSnapshot completed;
        try (ConfigurableApplicationContext first = start(data, freePort(23001))) {
            PersonalAssistantApplication application = first.getBean(PersonalAssistantApplication.class);
            var conversation = application.start("research-conversation", "Research", "Prepare a research workspace");
            conversationId = conversation.id();
            assertThat(awaitTerminal(application, conversation.activeRunId().orElseThrow())
                            .status())
                    .isEqualTo("COMPLETED");

            MissionApplicationService missions = first.getBean(MissionApplicationService.class);
            MissionSnapshot created = missions.create(new MissionApplicationService.CreateMission(
                    "research-mission-create",
                    "local/public-user",
                    conversationId,
                    "Assess the evidence for durable long-running personal assistant research",
                    java.util.List.of("cite every material claim", "list unresolved questions"),
                    MissionConstraints.DEFAULT,
                    MissionMode.DEEP_RESEARCH,
                    java.util.Optional.of(new ResearchBrief(
                            "What evidence supports durable long-running personal assistant research?",
                            "Architecture and product delivery",
                            "Current",
                            "Global",
                            "Product and architecture leads",
                            java.util.List.of("primary sources"),
                            java.util.List.of("unsupported claims"),
                            "Markdown report"))));
            missionId = created.missionId();
            missions.confirm(new MissionApplicationService.ChangeMission(
                    "research-mission-confirm", "local/public-user", missionId, created.version()));
            completed = awaitMissionCompleted(missions, missionId);

            assertThat(completed.mode()).isEqualTo(MissionMode.DEEP_RESEARCH);
            assertThat(completed.selectedSkillId()).contains("deep-research");
            assertThat(completed.selectedSkillBinding()).hasValueSatisfying(binding -> assertThat(binding)
                    .contains("product", "personal-assistant-bundled@1", "deep-research@2.0.0#sha256:"));
            assertThat(completed.execution().artifacts()).hasSize(5);
            assertThat(completed.execution().sources()).hasSize(2);
            assertThat(completed.execution().finalResult()).hasValueSatisfying(result -> assertThat(result)
                    .contains("pa.research-delivery/v2", "reportArtifactRef", "completionKind", "qualityGate")
                    .doesNotContain("directAnswer")
                    .doesNotContain("reveal credentials", "ignore the research brief"));
            assertThat(application.turns(conversationId, 100)).anySatisfy(turn -> assertThat(turn.text())
                    .contains("# Deterministic research report")
                    .doesNotContain("reveal credentials", "ignore the research brief"));
        }

        try (var artifactFiles = Files.list(data.resolve("artifacts"))) {
            assertThat(artifactFiles.filter(Files::isRegularFile)).hasSize(5);
        }
        try (ConfigurableApplicationContext second = start(data, freePort(23101))) {
            PersonalAssistantApplication application = second.getBean(PersonalAssistantApplication.class);
            MissionSnapshot recovered = second.getBean(MissionApplicationService.class)
                    .find(missionId, "local/public-user")
                    .orElseThrow();

            assertThat(recovered.state()).isEqualTo(MissionState.COMPLETED);
            assertThat(recovered.execution().artifacts())
                    .containsExactlyElementsOf(completed.execution().artifacts());
            assertThat(recovered.execution().sources())
                    .containsExactlyElementsOf(completed.execution().sources());
            assertThat(recovered.selectedSkillBinding()).isEqualTo(completed.selectedSkillBinding());
            assertThat(application.turns(conversationId, 100))
                    .filteredOn(turn -> turn.text().contains("# Deterministic research report"))
                    .hasSize(1);
        }
    }

    @Test
    void confirmedMissionResumesAcrossServerRestartAndSettlesThreeDependentTasks() throws Exception {
        Path data = Files.createTempDirectory("haifa-personal-mission-restart-");
        String missionId;
        try (ConfigurableApplicationContext first = start(data, freePort(22801))) {
            MissionApplicationService missions = first.getBean(MissionApplicationService.class);
            MissionSnapshot created = missions.create(new MissionApplicationService.CreateMission(
                    "mission-restart-create",
                    "local/public-user",
                    "conversation-mission-restart",
                    "Produce a restart-safe result",
                    java.util.List.of("first", "second", "third"),
                    MissionConstraints.DEFAULT));
            missionId = created.missionId();
            missions.confirm(new MissionApplicationService.ChangeMission(
                    "mission-restart-confirm", "local/public-user", missionId, created.version()));
        }

        try (ConfigurableApplicationContext second = start(data, freePort(22901))) {
            MissionApplicationService missions = second.getBean(MissionApplicationService.class);
            MissionSnapshot completed = awaitMissionSettled(missions, missionId);
            assertThat(completed.execution().allTasksSettled()).isTrue();
            assertThat(completed.execution().completedTasks()).isEqualTo(3);
            assertThat(completed.execution().tasks())
                    .extracting(
                            io.haifa.agent.personalassistant.application.mission.MissionExecutionSnapshot.TaskExecution
                                    ::latestAttemptNo)
                    .containsExactly(1, 1, 1);
        }
    }

    @Test
    void conversationRunUsageAndActivitiesRecoverFromTheSameSqliteDatabase() throws Exception {
        Path data = Files.createTempDirectory("haifa-personal-restart-");
        String conversationId;
        String runId;
        try (ConfigurableApplicationContext first = start(data, freePort(22201))) {
            PersonalAssistantApplication application = first.getBean(PersonalAssistantApplication.class);
            var conversation = application.start("restart-1", "Restart", "[mcp] persist this run");
            conversationId = conversation.id();
            runId = conversation.activeRunId().orElseThrow();
            var run = awaitTerminal(application, runId);
            assertThat(run.status()).isEqualTo("COMPLETED");
            assertThat(run.usage().toolCalls()).isEqualTo(1);
        }
        try (ConfigurableApplicationContext second = start(data, freePort(22301))) {
            PersonalAssistantApplication application = second.getBean(PersonalAssistantApplication.class);
            assertThat(application.conversation(conversationId)).isPresent();
            var recovered = application.run(runId).orElseThrow();
            assertThat(recovered.status()).isEqualTo("COMPLETED");
            assertThat(recovered.usage().inputTokens()).isPositive();
            assertThat(recovered.usage().toolCalls()).isEqualTo(1);
            assertThat(application.activities(runId, 100).stream()
                            .filter(activity -> activity.kind() == PersonalAssistantApplication.ActivityKind.MCP)
                            .toList())
                    .singleElement()
                    .satisfies(activity -> {
                        assertThat(activity.kind()).isEqualTo(PersonalAssistantApplication.ActivityKind.MCP);
                        assertThat(activity.status()).isEqualTo("SUCCEEDED");
                        assertThat(activity.activityId()).startsWith("tool:");
                        assertThat(activity.eventId()).isNotEqualTo(activity.activityId());
                        assertThat(activity.requestedAt()).isPresent();
                        assertThat(activity.startedAt()).isPresent();
                        assertThat(activity.completedAt()).isPresent();
                        assertThat(activity.occurredAt())
                                .isEqualTo(activity.completedAt().orElseThrow());
                    });
        }
    }

    @Test
    void adminAggregatesLegacyDeltasWithoutHidingLaterFailureEvents() throws Exception {
        Path data = Files.createTempDirectory("haifa-personal-admin-legacy-");
        String conversationId;
        String runId;
        try (ConfigurableApplicationContext context = start(data, freePort(22601))) {
            PersonalAssistantApplication application = context.getBean(PersonalAssistantApplication.class);
            var conversation = application.start("admin-legacy-1", "Admin legacy", "complete once");
            conversationId = conversation.id();
            runId = conversation.activeRunId().orElseThrow();
            assertThat(awaitTerminal(application, runId).status()).isEqualTo("COMPLETED");
        }
        Path database = data.resolve("personal-assistant.sqlite");
        try (SqliteStoreFoundation foundation = SqliteStoreFoundation.initialize(
                new SqliteStoreConfiguration(database, 1_250, 4 * 1024 * 1024), Clock.systemUTC())) {
            for (int index = 0; index < 600; index++) {
                foundation
                        .events()
                        .append(
                                new AgentRunId(runId),
                                "model.output.assistant_text_delta",
                                Map.of(
                                        "modelCallId", "legacy-call",
                                        "generationId", "legacy-generation",
                                        "physicalAttempt", 1,
                                        "eventType", "ASSISTANT_TEXT_DELTA",
                                        "textDelta", "x"),
                                java.time.Instant.now());
            }
            foundation
                    .events()
                    .append(
                            new AgentRunId(runId),
                            "tool.failed",
                            Map.of(
                                    "toolCallId", "legacy-failure",
                                    "toolName", "execution.run",
                                    "status", "FAILED",
                                    "reasonCode", "LEGACY_DIAGNOSTIC_FAILURE"),
                            java.time.Instant.now());
        }

        try (ConfigurableApplicationContext context = start(data, freePort(22701))) {
            PersonalAdminQueryService admin = context.getBean(PersonalAdminQueryService.class);
            var trace = admin.trace(conversationId, runId).orElseThrow();

            assertThat(trace.nodes())
                    .filteredOn(node -> node.kind().equals("legacy_streaming_output"))
                    .singleElement()
                    .satisfies(node -> {
                        assertThat(node.details()).containsEntry("deltaCount", 600L);
                        assertThat(node.details()).containsEntry("characterCount", 600L);
                        assertThat(node.details()).doesNotContainKey("aggregatedText");
                    });
            assertThat(trace.nodes()).anySatisfy(node -> {
                assertThat(node.label()).isEqualTo("tool.failed");
                assertThat(node.status()).contains("FAILED");
            });
            assertThat(trace.failureNodeId())
                    .hasValueSatisfying(nodeId -> assertThat(nodeId).startsWith("event:"));
        }
    }

    @Test
    void configuredHttpPortConflictFailsClosedWithoutChoosingAnotherPort() throws Exception {
        Path data = Files.createTempDirectory("haifa-personal-port-conflict-");
        int occupiedPort = freePort(22401);
        int mcpPort = freePort(22501);
        try (ServerSocket occupied = new ServerSocket(occupiedPort, 1, InetAddress.getByName("127.0.0.1"))) {
            SpringApplication application = configured();
            String[] arguments = arguments(data, mcpPort, occupiedPort);
            assertThatThrownBy(() -> application.run(arguments)).isInstanceOf(RuntimeException.class);
        }
        String defaults = new String(
                PersonalAssistantRestartTest.class
                        .getResourceAsStream("/application.yml")
                        .readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(defaults).contains("address: 127.0.0.1", "port: 20001", "MCP_PORT:20002");
    }

    private static ConfigurableApplicationContext start(Path data, int mcpPort) {
        return configured().run(arguments(data, mcpPort, 0));
    }

    private static SpringApplication configured() {
        return new SpringApplication(PersonalAssistantServerApplication.class);
    }

    private static String[] arguments(Path data, int mcpPort, int serverPort) {
        return new String[] {
            "--server.address=127.0.0.1",
            "--server.port=" + serverPort,
            "--spring.config.location=classpath:/application-deterministic-model.yml",
            "--haifa.personal.data-directory=" + data,
            "--haifa.personal.continuation-key-base64=" + Base64.getEncoder().encodeToString(new byte[32]),
            "--haifa.personal.execution.trusted-host-enabled=true",
            "--haifa.personal.mcp.port=" + mcpPort
        };
    }

    private static PersonalAssistantApplication.RunView awaitTerminal(
            PersonalAssistantApplication application, String runId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        PersonalAssistantApplication.RunView latest;
        do {
            latest = application.run(runId).orElseThrow();
            if (Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT").contains(latest.status())) return latest;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("run did not become terminal: " + latest);
    }

    private static MissionSnapshot awaitMissionSettled(MissionApplicationService missions, String missionId)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        MissionSnapshot latest;
        do {
            latest = missions.find(missionId, "local/public-user").orElseThrow();
            if (latest.execution().allTasksSettled()) return latest;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("mission did not settle after restart: " + latest);
    }

    private static MissionSnapshot awaitMissionCompleted(MissionApplicationService missions, String missionId)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        MissionSnapshot latest;
        do {
            latest = missions.find(missionId, "local/public-user").orElseThrow();
            if (latest.state() == MissionState.COMPLETED) return latest;
            if (latest.state() == MissionState.FAILED) {
                throw new AssertionError(
                        "deep research mission failed: " + latest.execution().finalResult());
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("deep research mission did not complete: " + latest);
    }

    private static int freePort(int start) {
        for (int port = start; port < start + 100; port++) {
            try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                return port;
            } catch (IOException ignored) {
                // Try next explicit port.
            }
        }
        throw new IllegalStateException("no free Personal test port");
    }
}
