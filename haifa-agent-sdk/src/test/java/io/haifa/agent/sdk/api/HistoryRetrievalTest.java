package io.haifa.agent.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.context.compression.*;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.model.api.*;
import io.haifa.agent.runtime.core.storage.*;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.sdk.conversation.*;
import io.haifa.agent.sdk.internal.InMemoryPersistenceContribution;
import io.haifa.agent.tool.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HistoryRetrievalTest {
    @Test
    void enablingHistoryOnlyAddsToolsAndDoesNotLeakToTheDefaultBuilder() throws Exception {
        List<AgentChatRequest> requests = new ArrayList<>();
        List<Map<String, Object>> accounting = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            AtomicInteger ids = new AtomicInteger();
            var store = new InMemoryRuntimeStore();
            var builder = SdkTestFixtures.builder("disabled")
                    .persistence(new InMemoryPersistenceContribution(RuntimePersistencePorts.inMemory(store)))
                    .identifierGenerator(() -> "id-" + ids.incrementAndGet())
                    .timeProvider(() -> Instant.parse("2026-11-04T00:00:00Z"))
                    .model(SdkTestFixtures.modelContribution(request -> {
                        requests.add(request);
                        return SdkTestFixtures.finalAnswer("done");
                    }));
            if (i == 0) builder.enableHistoryRetrieval();
            try (var agent = builder.build()) {
                var started = agent.conversations().start(new StartConversationCommand("start", "disabled", "hello"));
                agent.runs().await(started.runId());
                accounting.add(store.eventsFor(started.runId()).stream()
                        .filter(event -> event.type().equals("model.input-accounted"))
                        .findFirst()
                        .orElseThrow()
                        .data());
            }
        }
        assertThat((Long) accounting.getFirst().get("toolSchemaEstimatedTokens"))
                .isPositive();
        assertThat(accounting.getLast()).containsEntry("toolSchemaEstimatedTokens", 0L);
        assertThat(accounting
                        .getFirst()
                        .get("visibleFixedPrefixIdentity")
                        .equals(accounting.getLast().get("visibleFixedPrefixIdentity")))
                .isFalse();
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst().messages().equals(requests.getLast().messages()))
                .isTrue();
        assertThat(requests.getFirst().tools())
                .extracting(ModelToolSpecification::name)
                .containsExactlyInAnyOrder("history_search", "history_read");
        assertThat(requests.getFirst().options().equals(requests.getLast().options()))
                .isTrue();
        assertThat(requests.getLast().tools()).noneMatch(tool -> tool.name().startsWith("history_"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sdkModelFindsAnArchivedExternalResultAndReadsItsExactVersionWithoutRedispatch() throws Exception {
        String evidence = "historical-evidence-v1";
        String originalBody = evidence + "\n" + "x".repeat(24000) + "\nEND-v1";
        var historicDispatches = new AtomicInteger();
        var store = new InMemoryRuntimeStore();
        var dispatches = new AtomicInteger();
        var calls = new AtomicInteger();
        var seedProvider = new ToolProvider() {
            public ToolProviderId id() {
                return new ToolProviderId("seed-provider");
            }

            public ToolResult invoke(ToolInvocationRequest request) {
                dispatches.incrementAndGet();
                String path = (String) request.arguments().values().get("path");
                if (path.equals("historic.txt")) historicDispatches.incrementAndGet();
                return new ToolResult(
                        true,
                        "read completed",
                        Map.of("body", path.equals("historic.txt") ? originalBody : path),
                        List.of(),
                        List.of(),
                        true);
            }
        };
        var schema = new ToolSchema(
                "seed-input",
                "1",
                Map.of(
                        "$schema",
                        ToolSchema.DRAFT_2020_12,
                        "type",
                        "object",
                        "properties",
                        Map.of("path", Map.of("type", "string")),
                        "required",
                        List.of("path")));
        var definition = new ToolDefinition(
                new ToolName("seed_read"),
                new SemanticVersion("1.0.0"),
                seedProvider.id(),
                "Seed read",
                "Read fixture",
                schema,
                new ToolSchema("seed-output", "1", Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object")),
                ToolExecutionMode.IN_PROCESS,
                true,
                java.time.Duration.ofSeconds(5),
                "per-run",
                ToolIdempotency.IDEMPOTENT,
                ToolRisk.LOW,
                Set.of(),
                ToolResourceRequirements.none(),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "test",
                false,
                Set.of());
        AgentChatModel model = request -> {
            assertThat(request.tools())
                    .extracting(ModelToolSpecification::name)
                    .contains("history_search", "history_read");
            int ordinal = calls.getAndIncrement();
            if (ordinal == 0) return SdkTestFixtures.toolCall("seed_read", Map.of("path", "historic.txt"));
            if (ordinal == 1) {
                var data = request.messages().getLast().toolResultData();
                assertThat((String) data.get("content"))
                        .hasSizeLessThanOrEqualTo(2048)
                        .startsWith("read completed");
                var reference = new HashMap<>((Map<String, Object>) data.get("source"));
                reference.put("offset", "read completed\nbody: ".length() + originalBody.length() - "END-v1".length());
                reference.put("length", 64);
                return SdkTestFixtures.toolCall("history_read", reference);
            }
            if (ordinal == 2) {
                assertThat((String)
                                request.messages().getLast().toolResultData().get("content"))
                        .startsWith("END-v1");
                return SdkTestFixtures.finalAnswer("saved");
            }
            int step = ordinal - 1;
            if (step == 2) {
                assertThat(request.messages().stream()
                                .anyMatch(message -> message.content().contains(evidence)
                                        || message.toolResultData().toString().contains(evidence)))
                        .isFalse();
                return SdkTestFixtures.toolCall("history_search", Map.of("path", "historic.txt", "keyword", evidence));
            }
            if (step == 3) {
                var data = request.messages().getLast().toolResultData();
                var entry = ((List<Map<String, Object>>) data.get("entries")).getFirst();
                return SdkTestFixtures.toolCall(
                        "history_read",
                        Map.of(
                                "runId",
                                entry.get("runId"),
                                "messageId",
                                entry.get("messageId"),
                                "version",
                                entry.get("version"),
                                "toolCallId",
                                entry.get("toolCallId")));
            }
            if (step == 4)
                assertThat(request.messages().getLast().toolResultData().get("content"))
                        .asString()
                        .startsWith("read completed\nbody: " + evidence);
            if (step < 8) return SdkTestFixtures.toolCall("seed_read", Map.of("path", "fresh-" + step + ".txt"));
            if (step == 9) {
                assertThat(request.messages().stream()
                                .anyMatch(m -> m.toolResultData().toString().contains(evidence)))
                        .isFalse();
                return SdkTestFixtures.finalAnswer("Source withdrawn");
            }
            var toolData = request.messages().stream()
                    .filter(m -> m.role() == ModelMessageRole.TOOL)
                    .map(m -> m.toolResultData().toString())
                    .toList();
            assertThat(toolData).anyMatch(body -> body.contains(evidence));
            assertThat(toolData).anyMatch(body -> body.contains("fresh-4.txt"));
            assertThat(toolData).anyMatch(body -> body.contains("fresh-5.txt"));
            var historicalTool = store
                    .messagesAfter(
                            store.find(request.runId()).orElseThrow().sessionId(), MessageCursor.BEFORE_FIRST, 100)
                    .stream()
                    .filter(m -> m.role() == io.haifa.agent.core.message.MessageRole.TOOL)
                    .findFirst()
                    .orElseThrow();
            store.redactMessage(historicalTool.id());
            return SdkTestFixtures.toolCall("seed_read", Map.of("path", "fresh-8.txt"));
        };
        try (var agent = SdkTestFixtures.builder("history-sdk")
                .persistence(new InMemoryPersistenceContribution(RuntimePersistencePorts.inMemory(store)))
                .model(SdkTestFixtures.modelContribution(model))
                .toolRegistrations(
                        List.of(new ToolRegistration(new ToolAlias("seed_read"), definition, "seed", seedProvider)))
                .enableRunWorkingSet(2048)
                .build()) {
            var seed = agent.conversations().start(new StartConversationCommand("seed", "history", "save fixture"));
            assertThat(agent.runs().await(seed.runId()).status()).isEqualTo(AgentRunStatus.COMPLETED);

            var stored = store.toolCalls(seed.runId()).getFirst();
            assertThat(stored.result().orElseThrow().assets()).hasSize(1);
            assertThat(stored.result().orElseThrow().structuredData().get("body"))
                    .isEqualTo(originalBody);
            assertThat(store.toolCalls(seed.runId()).stream()
                            .filter(call -> call.toolName().equals("history_read"))
                            .allMatch(
                                    call -> call.result().orElseThrow().assets().isEmpty()))
                    .isTrue();
            var session = seed.record().sessionId();
            var messages = store.messagesAfter(session, MessageCursor.BEFORE_FIRST, 100);
            var compacted = new DeterministicContextCompressor()
                    .compress(new CompressionRequest(
                            new SummaryId("archived"),
                            new SummaryVersion(1),
                            session,
                            messages,
                            4,
                            Instant.parse("2026-11-04T00:00:00Z"),
                            CompressionPolicy.defaults().version()));
            store.compareAndSetValid(compacted.summary(), 0);
            var idle = agent.conversations().find(session).orElseThrow();
            var turn = agent.conversations()
                    .submit(new SubmitConversationTurnCommand(
                            session, idle.revision(), "read", "Find the saved evidence"));
            assertThat(agent.runs().await(turn.runId()).status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(dispatches).hasValue(6);
            assertThat(calls).hasValue(11);
            assertThat(historicDispatches).hasValue(1);
            assertThat(store.latestVersion(session)).isEqualTo(1);
            var source = messages.stream()
                    .filter(message -> message.role() == io.haifa.agent.core.message.MessageRole.TOOL)
                    .findFirst()
                    .orElseThrow();
            assertThat(store.message(source.id()).orElseThrow().status())
                    .isEqualTo(io.haifa.agent.core.message.MessageStatus.REDACTED);
            assertThat(agent.runs().toolCalls(turn.runId()).stream().anyMatch(call -> call.result()
                            .map(result -> result.structuredData().toString().contains(evidence))
                            .orElse(false)))
                    .isFalse();
        }
    }
}
