package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
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
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
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
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductVersion;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteSdkPersonalFixtureIT {

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
                Set.of(ModelCapability.TEXT_CHAT),
                8_192,
                1_024,
                Map.of(),
                Map.of());
        AtomicInteger responses = new AtomicInteger();
        AgentChatModel model = request -> {
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
        };
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
