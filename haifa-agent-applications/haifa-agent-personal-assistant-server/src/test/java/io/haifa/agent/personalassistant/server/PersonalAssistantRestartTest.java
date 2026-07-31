package io.haifa.agent.personalassistant.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
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
            assertThat(application.activities(runId, 100)).anySatisfy(activity -> {
                assertThat(activity.kind()).isEqualTo(PersonalAssistantApplication.ActivityKind.MCP);
                assertThat(activity.status()).isEqualTo("SUCCEEDED");
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
            "--haifa.personal.data-directory=" + data,
            "--haifa.personal.continuation-key-base64=" + Base64.getEncoder().encodeToString(new byte[32]),
            "--haifa.personal.model-providers[0].id=personal-local",
            "--haifa.personal.model-providers[0].display-name=Local acceptance",
            "--haifa.personal.model-providers[0].mode=deterministic",
            "--haifa.personal.model-providers[0].allow-deterministic=true",
            "--haifa.personal.model-providers[0].endpoint=http://127.0.0.1:20999",
            "--haifa.personal.model-providers[0].credential-reference=env://UNUSED",
            "--haifa.personal.model-providers[0].models[0].id=personal-test",
            "--haifa.personal.model-providers[0].models[0].display-name=Personal test",
            "--haifa.personal.model-providers[0].models[0].provider-model-id=personal-test",
            "--haifa.personal.default-model-id=personal-test",
            "--haifa.personal.execution.trusted-host-enabled=true",
            "--haifa.personal.mcp.port=" + mcpPort
        };
    }

    private static PersonalAssistantApplication.RunView awaitTerminal(
            PersonalAssistantApplication application, String runId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        PersonalAssistantApplication.RunView latest;
        do {
            latest = application.run(runId).orElseThrow();
            if (Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT").contains(latest.status())) return latest;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("run did not become terminal");
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
