package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemorySourceType;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.core.bootstrap.RuntimeControlOptions;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.conversation.ConversationQuery;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import io.haifa.agent.sdk.memory.MemoryListQuery;
import io.haifa.agent.sdk.memory.MemoryScopeSpec;
import io.haifa.agent.sdk.memory.PutMemoryCommand;
import io.haifa.agent.sdk.product.ChildAgentSpec;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfile;
import io.haifa.agent.sdk.product.ProductVersion;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteSdkPersonalFixtureIT {

    @Test
    void reopensParentAndConcurrentChildResultsWithoutReplayingDelegation(@TempDir Path directory) throws Exception {
        var protector =
                new AesGcmModelContinuationProtector(new SecretKeySpec(new byte[32], "AES"), new SecureRandom());
        var base = personalProfile();
        var profile = ProductProfile.create(
                        base.productId(),
                        base.productVersion(),
                        base.definitionId(),
                        base.definitionVersion(),
                        base.instructions(),
                        base.defaultRunProfile(),
                        AgentRunBudget.disabled(),
                        new AgentRunLimits(8, 1, 2, 30_000, 30_000, 8, 8, 2),
                        Set.of(),
                        Set.of())
                .withAllowedChildAgents(Set.of("reader"));
        var childModels = new CountDownLatch(2);
        var ids = new AtomicInteger();
        AgentRunId parentId;
        AgentSessionId sessionId;
        List<AgentRunId> childIds;
        var script = modelContribution(
                request -> {
                    var userText = request.messages().stream()
                            .filter(message -> message.role() == ModelMessageRole.USER)
                            .map(message -> message.content())
                            .toList();
                    if (request.tools().stream().noneMatch(tool -> tool.name().equals("task"))) {
                        assertThat(userText).noneMatch(text -> text.contains("parent-only"));
                        boolean left = userText.stream().anyMatch(text -> text.contains("read-left"));
                        boolean right = userText.stream().anyMatch(text -> text.contains("read-right"));
                        assertThat(left != right).isTrue();
                        childModels.countDown();
                        try {
                            assertThat(childModels.await(10, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                        return answer(left ? "left-finding" : "right-finding", List.of());
                    }
                    var findings = request.messages().stream()
                            .filter(message -> message.role() == ModelMessageRole.TOOL)
                            .map(message -> message.content())
                            .toList();
                    if (findings.isEmpty()) return answer("", List.of(delegate("left"), delegate("right")));
                    assertThat(findings)
                            .hasSize(2)
                            .anyMatch(text -> text.contains("left-finding"))
                            .anyMatch(text -> text.contains("right-finding"));
                    return answer("left-finding and right-finding", List.of());
                },
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING));
        try (var agent = delegationAgent(profile, sqliteContributions(directory, protector), ids, script)) {
            var started = agent.conversations()
                    .start(new StartConversationCommand("delegate-start", "Parent", "parent-only request"));
            parentId = started.runId();
            sessionId = started.record().sessionId();
            assertThat(agent.runs()
                            .await(parentId, Duration.ofSeconds(20))
                            .orElseThrow()
                            .status())
                    .isEqualTo(AgentRunStatus.COMPLETED);
            childIds = agent.runs().children(parentId).stream()
                    .map(child -> child.runId())
                    .toList();
            assertThat(childIds).hasSize(2).doesNotHaveDuplicates();
        }

        var callsAfterReopen = new AtomicInteger();
        var reopenedModel = modelContribution(
                request -> {
                    callsAfterReopen.incrementAndGet();
                    assertThat(request.messages())
                            .anyMatch(message -> message.role() == ModelMessageRole.ASSISTANT
                                    && message.content().contains("left-finding and right-finding"));
                    return answer("follow-up used saved findings", List.of());
                },
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING));
        try (var reopened = delegationAgent(profile, sqliteContributions(directory, protector), ids, reopenedModel)) {
            assertThat(reopened.runs().children(parentId))
                    .allSatisfy(child -> {
                        assertThat(child.parentRunId()).isEqualTo(parentId);
                        assertThat(child.status()).isEqualTo(AgentRunStatus.COMPLETED);
                    })
                    .extracting(child -> child.runId())
                    .containsExactlyInAnyOrderElementsOf(childIds);
            assertThat(childIds.stream().map(id -> reopened.runs()
                            .find(id)
                            .orElseThrow()
                            .result()
                            .orElseThrow()
                            .summary()))
                    .containsExactlyInAnyOrder("left-finding", "right-finding");
            assertThat(callsAfterReopen).hasValue(0);
            var session = reopened.conversations().find(sessionId).orElseThrow();
            var followUp = reopened.conversations()
                    .submit(new SubmitConversationTurnCommand(
                            sessionId, session.revision(), "delegate-follow-up", "Continue with the saved findings"));
            assertThat(reopened.runs()
                            .await(followUp.runId(), Duration.ofSeconds(20))
                            .orElseThrow()
                            .result())
                    .hasValueSatisfying(
                            result -> assertThat(result.summary()).isEqualTo("follow-up used saved findings"));
            assertThat(reopened.runs().children(followUp.runId())).isEmpty();
            assertThat(callsAfterReopen).hasValue(1);
        }
    }

    private static HaifaAgent delegationAgent(
            ProductProfile profile, SqliteSdkContributions store, AtomicInteger ids, ModelContribution model) {
        var runProfile = profile.defaultRunProfile();
        return HaifaAgents.builder(profile)
                .model(model)
                .persistence(store.persistence())
                .conversation(store.conversation())
                .identifierGenerator(() -> "delegation-sqlite-" + ids.incrementAndGet())
                .timeProvider(() -> SqliteTestSupport.NOW)
                .childAgent(ChildAgentSpec.of("reader", "Reader", "Read only your assigned brief.", Set.of()))
                .runProfile(new ProductRunProfile(
                        runProfile.id(),
                        runProfile.version(),
                        model.snapshot().modelId().value(),
                        AgentRunType.CHAT,
                        profile.budget(),
                        profile.limits(),
                        RuntimeControlOptions.unifiedWorkingSetOptions(Map.of(), 4096)))
                .build();
    }

    private static ModelToolCall delegate(String side) {
        return new ModelToolCall(
                new ProviderToolCallCorrelationId("delegate-" + side),
                "task",
                Map.of("agent", "reader", "objective", "read-" + side));
    }

    private static AgentChatResponse answer(String text, List<ModelToolCall> tools) {
        return new AgentChatResponse(
                "r",
                "personal-test-chat",
                text,
                tools,
                tools.isEmpty() ? ModelFinishReason.STOP : ModelFinishReason.TOOL_CALLS,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    @Test
    void storesConversationMemoryWithProvenanceAndRecoversItThroughSdk(@TempDir Path directory) throws Exception {
        ProductProfile profile = personalMemoryProfile();
        var protector =
                new AesGcmModelContinuationProtector(new SecretKeySpec(new byte[32], "AES"), new SecureRandom());
        AtomicInteger ids = new AtomicInteger();
        String sessionId;

        SqliteSdkProductContributions first = sqliteProductContributions(directory, protector);
        try (HaifaAgent agent = HaifaAgents.builder(profile)
                .model(modelContribution())
                .persistence(first.persistence())
                .conversation(first.conversation())
                .memory(first.memory())
                .callerProvider(SqliteSdkPersonalFixtureIT::memoryReviewer)
                .identifierGenerator(() -> "memory-fixture-" + ids.incrementAndGet())
                .timeProvider(() -> SqliteTestSupport.NOW)
                .build()) {
            var conversation =
                    agent.conversations().start(new StartConversationCommand("memory-start", "Memory", "Use Java"));
            agent.runs().await(conversation.runId());
            var turn = agent.conversations()
                    .turns(conversation.record().sessionId())
                    .getFirst();
            var memory = agent.memories()
                    .orElseThrow()
                    .put(new PutMemoryCommand(
                            MemoryScopeSpec.session(
                                    conversation.record().sessionId().value()),
                            MemoryKind.PREFERENCE,
                            "language",
                            "Java",
                            Optional.of(new MemorySourceRef(MemorySourceType.MESSAGE, turn.messageId())),
                            Optional.of(SqliteTestSupport.NOW)));
            assertThat(memory.revision()).isEqualTo(1);
            sessionId = conversation.record().sessionId().value();
        }

        SqliteSdkProductContributions reopenedStore = sqliteProductContributions(directory, protector);
        try (HaifaAgent reopened = HaifaAgents.builder(profile)
                .model(modelContribution())
                .persistence(reopenedStore.persistence())
                .conversation(reopenedStore.conversation())
                .memory(reopenedStore.memory())
                .callerProvider(SqliteSdkPersonalFixtureIT::memoryReviewer)
                .identifierGenerator(() -> "memory-reopen-" + ids.incrementAndGet())
                .timeProvider(() -> SqliteTestSupport.NOW)
                .build()) {
            var page = reopened.memories()
                    .orElseThrow()
                    .list(new MemoryListQuery(
                            MemoryScopeSpec.session(sessionId),
                            Set.of(MemoryKind.PREFERENCE),
                            Optional.empty(),
                            Optional.empty(),
                            10));
            assertThat(page.items()).singleElement().satisfies(memory -> {
                assertThat(memory.content()).isEqualTo("Java");
                assertThat(memory.source()).isPresent();
            });
        }
    }

    @Test
    void assemblesPersonalProfileAndRecoversConversationWithoutCodingProductState(@TempDir Path directory)
            throws Exception {
        ProductProfile profile = personalProfile();
        var protector =
                new AesGcmModelContinuationProtector(new SecretKeySpec(new byte[32], "AES"), new SecureRandom());
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator identifiers = () -> "personal-sqlite-" + ids.incrementAndGet();
        String sessionId;

        SqliteSdkContributions firstStore = sqliteContributions(directory, protector);
        try (HaifaAgent agent = HaifaAgents.builder(profile)
                .model(modelContribution())
                .persistence(firstStore.persistence())
                .conversation(firstStore.conversation())
                .identifierGenerator(identifiers)
                .timeProvider(() -> SqliteTestSupport.NOW)
                .build()) {
            var started =
                    agent.conversations().start(new StartConversationCommand("start-1", "Personal chat", "hello"));
            agent.runs().await(started.runId());
            var idle = agent.conversations().find(started.record().sessionId()).orElseThrow();
            var submitted = agent.conversations()
                    .submit(new SubmitConversationTurnCommand(idle.sessionId(), idle.revision(), "turn-2", "continue"));
            agent.runs().await(submitted.runId());
            var completed =
                    agent.conversations().find(started.record().sessionId()).orElseThrow();

            assertThat(completed.sessionId()).isEqualTo(started.record().sessionId());
            assertThat(agent.conversations().turns(started.record().sessionId()))
                    .extracting("text")
                    .containsExactly("hello", "answer-1", "continue", "answer-2");
            sessionId = started.record().sessionId().value();
        }

        SqliteSdkContributions reopenedStore = sqliteContributions(directory, protector);
        try (HaifaAgent reopened = HaifaAgents.builder(profile)
                .model(modelContribution())
                .persistence(reopenedStore.persistence())
                .conversation(reopenedStore.conversation())
                .identifierGenerator(identifiers)
                .timeProvider(() -> SqliteTestSupport.NOW)
                .build()) {
            var page = reopened.conversations().list(ConversationQuery.active(10));
            assertThat(page.items()).singleElement().satisfies(conversation -> {
                assertThat(conversation.sessionId().value()).isEqualTo(sessionId);
            });
            assertThat(reopened.conversations().turns(page.items().getFirst().sessionId()).stream()
                            .map(turn -> turn.text())
                            .toList())
                    .containsExactly("hello", "answer-1", "continue", "answer-2");
        }

        assertNoCodingProductState(directory);
    }

    @Test
    void coordinatesConcurrentStartAcrossTwoSdkInstancesExactlyOnce(@TempDir Path directory) throws Exception {
        ProductProfile profile = personalProfile();
        var protector =
                new AesGcmModelContinuationProtector(new SecretKeySpec(new byte[32], "AES"), new SecureRandom());
        AtomicInteger ids = new AtomicInteger();
        SqliteSdkContributions firstStore = sqliteContributions(directory, protector);
        SqliteSdkContributions secondStore = sqliteContributions(directory, protector);

        try (HaifaAgent first = agent(profile, firstStore, ids, "first");
                HaifaAgent second = agent(profile, secondStore, ids, "second");
                var executor = Executors.newFixedThreadPool(3)) {
            CountDownLatch startGate = new CountDownLatch(1);
            var firstStart = executor.submit(() -> {
                startGate.await();
                return first.conversations().start(new StartConversationCommand("shared-start", "Shared", "hello"));
            });
            var secondStart = executor.submit(() -> {
                startGate.await();
                return second.conversations().start(new StartConversationCommand("shared-start", "Shared", "hello"));
            });
            startGate.countDown();

            var startedByFirst = firstStart.get();
            var startedBySecond = secondStart.get();
            assertThat(startedBySecond.record().sessionId())
                    .isEqualTo(startedByFirst.record().sessionId());
            assertThat(startedBySecond.runId()).isEqualTo(startedByFirst.runId());
            waitUntilTerminal(first, startedByFirst.runId());
            waitUntilTerminal(second, startedByFirst.runId());

            var idle = first.conversations()
                    .find(startedByFirst.record().sessionId())
                    .orElseThrow();
            var submitted = first.conversations()
                    .submit(new SubmitConversationTurnCommand(idle.sessionId(), idle.revision(), "turn-2", "continue"));
            waitUntilTerminal(first, submitted.runId());
            assertThat(first.conversations().turns(startedByFirst.record().sessionId()).stream()
                            .map(turn -> turn.text())
                            .toList())
                    .contains("hello", "continue");
            assertThat(first.conversations().list(ConversationQuery.active(10)).items())
                    .singleElement()
                    .extracting("sessionId")
                    .isEqualTo(idle.sessionId());
        }
    }

    private static ProductProfile personalProfile() {
        return ProductProfile.create(
                new ProductId("personal-assistant"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("personal-assistant-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "Act as a careful personal assistant.",
                new io.haifa.agent.sdk.product.ProductRunProfileRef("personal-chat", "1.0.0"),
                new AgentRunBudget(10_000, 10_000, 10_000, 8, 8, 0, "USD", 1_000),
                new AgentRunLimits(8, 0, 1, 30_000, 30_000),
                Set.of(),
                Set.of());
    }

    private static ProductProfile personalMemoryProfile() {
        return personalProfile();
    }

    private static HaifaAgent agent(
            ProductProfile profile, SqliteSdkContributions store, AtomicInteger ids, String instance) {
        return HaifaAgents.builder(profile)
                .model(modelContribution())
                .persistence(store.persistence())
                .conversation(store.conversation())
                .identifierGenerator(() -> "personal-" + instance + "-" + ids.incrementAndGet())
                .timeProvider(() -> SqliteTestSupport.NOW)
                .build();
    }

    private static void waitUntilTerminal(HaifaAgent agent, io.haifa.agent.core.run.AgentRunId runId) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            var snapshot = agent.runs().find(runId).orElseThrow();
            if (snapshot.status().isTerminal()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Run did not become terminal");
    }

    private static ModelContribution modelContribution() {
        var responses = new AtomicInteger();
        return modelContribution(
                request -> {
                    int response = responses.incrementAndGet();
                    return new AgentChatResponse(
                            "response-" + response,
                            "personal-test-chat",
                            "answer-" + response,
                            List.of(),
                            ModelFinishReason.STOP,
                            ModelUsage.unpriced(1, 1),
                            "",
                            Map.of());
                },
                Set.of(ModelCapability.TEXT_CHAT));
    }

    private static ModelContribution modelContribution(AgentChatModel model, Set<ModelCapability> capabilities) {
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("personal-test"),
                "1.0",
                new ModelDefinitionId("personal-test-chat"),
                "1.0",
                "personal-test-chat",
                "personal-test-adapter",
                "1.0",
                new ApiStyleId("personal-test-style"),
                "standard",
                URI.create("https://model.invalid/v1"),
                new CredentialRef("credential:personal-test"),
                true,
                capabilities,
                8_192,
                1_024,
                Map.of(),
                Map.of());
        return new ModelContribution(
                Map.of(ModelAdapterCoordinate.from(snapshot), model),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));
    }

    private static SqliteSdkContributions sqliteContributions(
            Path directory, AesGcmModelContinuationProtector protector) {
        return SqliteSdkContributions.initialize(
                SqliteTestSupport.configuration(directory), SqliteTestSupport.CLOCK, protector);
    }

    private static SqliteSdkProductContributions sqliteProductContributions(
            Path directory, AesGcmModelContinuationProtector protector) {
        return SqliteSdkProductContributions.initialize(
                SqliteTestSupport.configuration(directory),
                SqliteTestSupport.CLOCK,
                protector,
                io.haifa.agent.sdk.product.ProductMemoryPolicy.safeDefault(),
                io.haifa.agent.sdk.product.ProductArtifactPolicy.disabled());
    }

    private static SdkCaller memoryReviewer() {
        return SdkCaller.defaultPublicUser();
    }

    private static void assertNoCodingProductState(Path directory) throws Exception {
        try (SqliteConnectionFactory connections =
                new SqliteConnectionFactory(SqliteTestSupport.configuration(directory))) {
            connections.initialize();
            try (var connection = connections.openConnection()) {
                List<String> codingTables = List.of(
                        "project_product_session",
                        "coding_session_activity",
                        "coding_session_command",
                        "coding_follow_up",
                        "coding_session_event_cursor");
                for (String table : codingTables) {
                    try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                            ResultSet result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getInt(1))
                                .as("Expected table %s to contain no rows", table)
                                .isZero();
                    }
                }
            }
        }
    }
}
