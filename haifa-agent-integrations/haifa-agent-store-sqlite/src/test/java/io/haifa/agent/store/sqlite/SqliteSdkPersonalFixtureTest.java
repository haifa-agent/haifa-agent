package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.memory.api.MemoryEvidenceRef;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemorySourceType;
import io.haifa.agent.memory.api.MemoryStatus;
import io.haifa.agent.memory.api.TextMemoryContent;
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
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.conversation.ConversationException;
import io.haifa.agent.sdk.conversation.ConversationQuery;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import io.haifa.agent.sdk.memory.MemoryListQuery;
import io.haifa.agent.sdk.memory.MemoryScopeSpec;
import io.haifa.agent.sdk.memory.ProposeMemoryCommand;
import io.haifa.agent.sdk.memory.ReviewMemoryCandidateCommand;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductCapabilityId;
import io.haifa.agent.sdk.product.ProductCapabilityRequirement;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
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

class SqliteSdkPersonalFixtureTest {
    private static final ProductContributionCoordinate MODEL =
            new ProductContributionCoordinate("model.personal-test", "1.0");
    private static final ProductContributionCoordinate PERSISTENCE =
            new ProductContributionCoordinate("persistence.sqlite", "1.0");
    private static final ProductContributionCoordinate CONVERSATION =
            new ProductContributionCoordinate("conversation.sqlite", "1.0");
    private static final ProductContributionCoordinate MEMORY =
            new ProductContributionCoordinate("memory.sqlite", "1.0");

    @Test
    void promotesConversationEvidenceToMemoryAndRecoversItThroughSdk(@TempDir Path directory) throws Exception {
        ProductProfile profile = personalMemoryProfile();
        var protector =
                new AesGcmModelContinuationProtector(new SecretKeySpec(new byte[32], "AES"), new SecureRandom());
        AtomicInteger ids = new AtomicInteger();
        String sessionId;

        SqliteSdkProductContributions first = sqliteProductContributions(directory, protector);
        try (HaifaAgent agent = HaifaAgents.builder(profile)
                .contribute(modelContribution())
                .contribute(first.persistence())
                .contribute(first.conversation())
                .contribute(first.memory())
                .callerProvider(SqliteSdkPersonalFixtureTest::memoryReviewer)
                .identifierGenerator(() -> "memory-fixture-" + ids.incrementAndGet())
                .timeProvider(() -> SqliteTestSupport.NOW)
                .build()) {
            var conversation =
                    agent.conversations().start(new StartConversationCommand("memory-start", "Memory", "Use Java"));
            agent.runs().await(conversation.activeRunId().orElseThrow());
            var turn = agent.conversations().turns(conversation.sessionId()).getFirst();
            String contentDigest = messageDigest(directory, turn.messageId());
            MemorySourceRef source = new MemorySourceRef(MemorySourceType.MESSAGE, turn.messageId(), Optional.empty());
            var candidate = agent.memories()
                    .orElseThrow()
                    .propose(new ProposeMemoryCommand(
                            "memory-propose",
                            MemoryScopeSpec.session(conversation.sessionId().value()),
                            MemoryKind.PREFERENCE,
                            "language",
                            new TextMemoryContent("Java"),
                            List.of(source),
                            List.of(new MemoryEvidenceRef(source, contentDigest)),
                            Optional.empty()));
            assertThat(candidate.status()).isEqualTo(io.haifa.agent.memory.api.MemoryCandidateStatus.PENDING);
            agent.memories()
                    .orElseThrow()
                    .approve(new ReviewMemoryCandidateCommand(candidate.id(), candidate.revision(), "memory-approve"));
            sessionId = conversation.sessionId().value();
        }

        SqliteSdkProductContributions reopenedStore = sqliteProductContributions(directory, protector);
        try (HaifaAgent reopened = HaifaAgents.builder(profile)
                .contribute(modelContribution())
                .contribute(reopenedStore.persistence())
                .contribute(reopenedStore.conversation())
                .contribute(reopenedStore.memory())
                .callerProvider(SqliteSdkPersonalFixtureTest::memoryReviewer)
                .identifierGenerator(() -> "memory-reopen-" + ids.incrementAndGet())
                .timeProvider(() -> SqliteTestSupport.NOW)
                .build()) {
            var page = reopened.memories()
                    .orElseThrow()
                    .memories(new MemoryListQuery(
                            MemoryScopeSpec.session(sessionId),
                            Set.of(MemoryStatus.ACTIVE),
                            Set.of(MemoryKind.PREFERENCE),
                            Optional.empty(),
                            Optional.empty(),
                            10));
            assertThat(page.items()).singleElement().satisfies(memory -> {
                assertThat(memory.status()).isEqualTo(MemoryStatus.ACTIVE);
                assertThat(memory.content().orElseThrow().boundedText()).isEqualTo("Java");
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
        String assemblyDigest;
        String sessionId;

        SqliteSdkContributions firstStore = sqliteContributions(directory, protector);
        try (HaifaAgent agent = HaifaAgents.builder(profile)
                .contribute(modelContribution())
                .contribute(firstStore.persistence())
                .contribute(firstStore.conversation())
                .identifierGenerator(identifiers)
                .timeProvider(() -> SqliteTestSupport.NOW)
                .build()) {
            assemblyDigest = agent.assembly().assemblyDigest();
            assertThat(agent.assembly().contributions().keySet())
                    .containsExactlyInAnyOrder(
                            ProductCapabilities.MODEL,
                            ProductCapabilities.PERSISTENCE,
                            ProductCapabilities.CONVERSATION);

            var started =
                    agent.conversations().start(new StartConversationCommand("start-1", "Personal chat", "hello"));
            agent.runs().await(started.activeRunId().orElseThrow());
            var idle = agent.conversations().find(started.sessionId()).orElseThrow();
            var submitted = agent.conversations()
                    .submit(new SubmitConversationTurnCommand(idle.sessionId(), idle.revision(), "turn-2", "continue"));
            agent.runs().await(submitted.activeRunId().orElseThrow());
            var completed = agent.conversations().find(started.sessionId()).orElseThrow();

            assertThat(completed.activeRunId()).isEmpty();
            assertThat(agent.conversations().turns(started.sessionId()))
                    .extracting("text")
                    .containsExactly("hello", "answer-1", "continue", "answer-2");
            sessionId = started.sessionId().value();
        }

        SqliteSdkContributions reopenedStore = sqliteContributions(directory, protector);
        try (HaifaAgent reopened = HaifaAgents.builder(profile)
                .contribute(modelContribution())
                .contribute(reopenedStore.persistence())
                .contribute(reopenedStore.conversation())
                .identifierGenerator(identifiers)
                .timeProvider(() -> SqliteTestSupport.NOW)
                .build()) {
            assertThat(reopened.assembly().assemblyDigest()).isEqualTo(assemblyDigest);
            var page = reopened.conversations().list(ConversationQuery.active(10));
            assertThat(page.items()).singleElement().satisfies(conversation -> {
                assertThat(conversation.sessionId().value()).isEqualTo(sessionId);
                assertThat(conversation.activeRunId()).isEmpty();
            });
            assertThat(reopened.conversations().turns(page.items().getFirst().sessionId()).stream()
                            .map(turn -> turn.text())
                            .toList())
                    .containsExactly("hello", "answer-1", "continue", "answer-2");
        }

        assertNoCodingProductTables(directory);
    }

    @Test
    void coordinatesConcurrentStartAndSubmitAcrossTwoSdkInstances(@TempDir Path directory) throws Exception {
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
            assertThat(startedBySecond.sessionId()).isEqualTo(startedByFirst.sessionId());
            assertThat(startedBySecond.activeRunId()).isEqualTo(startedByFirst.activeRunId());
            waitUntilTerminal(first, startedByFirst.activeRunId().orElseThrow());
            waitUntilTerminal(second, startedByFirst.activeRunId().orElseThrow());
            var idle = waitUntilIdle(first, startedByFirst.sessionId());
            assertThat(idle.activeRunId()).isEmpty();

            CountDownLatch submitGate = new CountDownLatch(1);
            var firstSubmit = executor.submit(() -> submit(
                    first,
                    new SubmitConversationTurnCommand(idle.sessionId(), idle.revision(), "first-submit", "from first"),
                    submitGate));
            var secondSubmit = executor.submit(() -> submit(
                    second,
                    new SubmitConversationTurnCommand(
                            idle.sessionId(), idle.revision(), "second-submit", "from second"),
                    submitGate));
            var racingReconciler = executor.submit(() -> {
                submitGate.await();
                return second.conversations().find(idle.sessionId());
            });
            submitGate.countDown();
            List<Object> submitResults = List.of(firstSubmit.get(), secondSubmit.get());
            java.util.Optional<io.haifa.agent.sdk.conversation.ConversationRecord> reconciled = racingReconciler.get();
            assertThat(reconciled).isPresent();

            assertThat(submitResults.stream()
                            .filter(io.haifa.agent.sdk.conversation.ConversationRecord.class::isInstance))
                    .hasSize(1);
            assertThat(submitResults.stream().filter(ConversationException.class::isInstance))
                    .singleElement()
                    .satisfies(failure -> {
                        ConversationException error = (ConversationException) failure;
                        assertThat(error.code()).isIn("CONVERSATION_ACTIVE", "CONVERSATION_REVISION_MISMATCH");
                        assertThat(error.operation()).isEqualTo("conversation.submit");
                        assertThat(error.correlation()).matches("[0-9a-f]{16}");
                        assertThat(error.getMessage())
                                .doesNotContain("from first", "from second", directory.toString());
                        assertThat(error.getCause()).isNull();
                    });
            var accepted = submitResults.stream()
                    .filter(io.haifa.agent.sdk.conversation.ConversationRecord.class::isInstance)
                    .map(io.haifa.agent.sdk.conversation.ConversationRecord.class::cast)
                    .findFirst()
                    .orElseThrow();
            waitUntilTerminal(first, accepted.activeRunId().orElseThrow());
            assertThat(first.conversations()
                            .find(idle.sessionId())
                            .orElseThrow()
                            .activeRunId())
                    .isEmpty();
            assertThat(first.conversations().list(ConversationQuery.active(10)).items())
                    .singleElement()
                    .extracting("sessionId")
                    .isEqualTo(idle.sessionId());
        }
    }

    private static ProductProfile personalProfile() {
        Map<ProductCapabilityId, ProductCapabilityRequirement> requirements = Map.of(
                ProductCapabilities.MODEL,
                ProductCapabilityRequirement.required(
                        ProductCapabilities.MODEL, Set.of(MODEL), ProductProviderSuitability.DEVELOPMENT),
                ProductCapabilities.PERSISTENCE,
                ProductCapabilityRequirement.required(
                        ProductCapabilities.PERSISTENCE, Set.of(PERSISTENCE), ProductProviderSuitability.PRODUCTION),
                ProductCapabilities.CONVERSATION,
                ProductCapabilityRequirement.required(
                        ProductCapabilities.CONVERSATION, Set.of(CONVERSATION), ProductProviderSuitability.PRODUCTION));
        return ProductProfile.create(
                new ProductId("personal-assistant"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("personal-assistant-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "personal-chat",
                "1.0.0",
                "Act as a careful personal assistant.",
                new AgentRunBudget(10_000, 10_000, 10_000, 8, 8, 0, "USD", 1_000),
                new AgentRunLimits(8, 0, 1, 30_000, 30_000),
                requirements,
                Set.of(),
                Set.of(),
                Set.of());
    }

    private static ProductProfile personalMemoryProfile() {
        Map<ProductCapabilityId, ProductCapabilityRequirement> requirements =
                new java.util.HashMap<>(personalProfile().capabilityRequirements());
        requirements.put(
                ProductCapabilities.MEMORY,
                ProductCapabilityRequirement.required(
                        ProductCapabilities.MEMORY, Set.of(MEMORY), ProductProviderSuitability.PRODUCTION));
        ProductProfile base = personalProfile();
        return ProductProfile.create(
                base.productId(),
                base.productVersion(),
                base.definitionId(),
                base.definitionVersion(),
                base.runProfileId(),
                base.runProfileVersion(),
                base.instructions(),
                base.budget(),
                base.limits(),
                base.policies(),
                requirements,
                base.allowedTools(),
                base.allowedSkills(),
                base.allowedExtensions());
    }

    private static HaifaAgent agent(
            ProductProfile profile, SqliteSdkContributions store, AtomicInteger ids, String instance) {
        return HaifaAgents.builder(profile)
                .contribute(modelContribution())
                .contribute(store.persistence())
                .contribute(store.conversation())
                .identifierGenerator(() -> "personal-" + instance + "-" + ids.incrementAndGet())
                .timeProvider(() -> SqliteTestSupport.NOW)
                .build();
    }

    private static Object submit(HaifaAgent agent, SubmitConversationTurnCommand command, CountDownLatch startGate)
            throws Exception {
        startGate.await();
        try {
            return agent.conversations().submit(command);
        } catch (Exception expected) {
            return expected;
        }
    }

    private static void waitUntilTerminal(HaifaAgent agent, io.haifa.agent.core.run.AgentRunId runId) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            var snapshot = agent.runs().find(runId).orElseThrow();
            if (snapshot.status().isTerminal()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Run did not become terminal");
    }

    private static io.haifa.agent.sdk.conversation.ConversationRecord waitUntilIdle(
            HaifaAgent agent, io.haifa.agent.core.session.AgentSessionId sessionId) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            var conv = agent.conversations().find(sessionId).orElseThrow();
            if (conv.activeRunId().isEmpty()) return conv;
            Thread.sleep(10);
        }
        throw new AssertionError("Conversation did not become idle");
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
                metadata(
                        MODEL,
                        ProductCapabilities.MODEL,
                        snapshot.configurationDigest(),
                        ProductProviderSuitability.DEVELOPMENT),
                Map.of(ModelAdapterCoordinate.from(snapshot), model),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));
    }

    private static SqliteSdkContributions sqliteContributions(
            Path directory, AesGcmModelContinuationProtector protector) {
        return SqliteSdkContributions.initialize(
                SqliteTestSupport.configuration(directory),
                SqliteTestSupport.CLOCK,
                protector,
                metadata(
                        PERSISTENCE,
                        ProductCapabilities.PERSISTENCE,
                        SdkConfigurationDigest.sha256("sqlite-runtime-v5"),
                        ProductProviderSuitability.PRODUCTION),
                metadata(
                        CONVERSATION,
                        ProductCapabilities.CONVERSATION,
                        SdkConfigurationDigest.sha256("sqlite-conversation-v1"),
                        ProductProviderSuitability.PRODUCTION));
    }

    private static SqliteSdkProductContributions sqliteProductContributions(
            Path directory, AesGcmModelContinuationProtector protector) {
        return SqliteSdkProductContributions.initialize(
                SqliteTestSupport.configuration(directory),
                SqliteTestSupport.CLOCK,
                protector,
                metadata(
                        PERSISTENCE,
                        ProductCapabilities.PERSISTENCE,
                        SdkConfigurationDigest.sha256("sqlite-runtime-v6"),
                        ProductProviderSuitability.PRODUCTION),
                metadata(
                        CONVERSATION,
                        ProductCapabilities.CONVERSATION,
                        SdkConfigurationDigest.sha256("sqlite-conversation-v1"),
                        ProductProviderSuitability.PRODUCTION),
                metadata(
                        MEMORY,
                        ProductCapabilities.MEMORY,
                        SdkConfigurationDigest.sha256("sqlite-memory-v1"),
                        ProductProviderSuitability.PRODUCTION));
    }

    private static SdkCaller memoryReviewer() {
        SdkCaller base = SdkCaller.defaultPublicUser();
        return new SdkCaller(base.tenant(), base.principal(), Set.of("memory:read", "memory:propose", "memory:review"));
    }

    private static String messageDigest(Path directory, String messageId) throws Exception {
        try (SqliteConnectionFactory connections =
                new SqliteConnectionFactory(SqliteTestSupport.configuration(directory))) {
            connections.initialize();
            try (var connection = connections.openConnection();
                    var statement = connection.prepareStatement(
                            "SELECT content_hash FROM session_message WHERE message_id=?")) {
                statement.setString(1, messageId);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    return result.getString(1);
                }
            }
        }
    }

    private static SdkContributionMetadata metadata(
            ProductContributionCoordinate coordinate,
            ProductCapabilityId capability,
            String digest,
            ProductProviderSuitability suitability) {
        return new SdkContributionMetadata(coordinate, capability, digest, suitability, "safe test contribution");
    }

    private static void assertNoCodingProductTables(Path directory) throws Exception {
        try (SqliteConnectionFactory connections =
                new SqliteConnectionFactory(SqliteTestSupport.configuration(directory))) {
            connections.initialize();
            try (var connection = connections.openConnection();
                    var statement = connection.prepareStatement(
                            """
                            SELECT name
                            FROM sqlite_master
                            WHERE type = 'table'
                              AND name IN (
                                'project_product_session',
                                'coding_session_activity',
                                'coding_session_command',
                                'coding_follow_up',
                                'coding_session_event_cursor'
                              )
                            ORDER BY name
                            """);
                    ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isFalse();
            }
        }
    }
}
