package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.conversation.ConversationQuery;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
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
import java.util.Set;
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

    private static ModelContribution modelContribution() {
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("personal-test"),
                "1.0",
                new ModelDefinitionId("personal-test-chat"),
                "1.0",
                "personal-test-chat",
                "personal-test-adapter",
                "1.0",
                URI.create("https://model.invalid/v1"),
                new CredentialRef("credential:personal-test"),
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
                model,
                snapshot);
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
