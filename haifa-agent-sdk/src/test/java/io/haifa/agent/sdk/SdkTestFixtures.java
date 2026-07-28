package io.haifa.agent.sdk;

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
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.InMemoryConversationContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.internal.InMemoryPersistenceContribution;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductCapabilityId;
import io.haifa.agent.sdk.product.ProductCapabilityRequirement;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import io.haifa.agent.sdk.product.ProductVersion;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class SdkTestFixtures {
    public static final ProductContributionCoordinate MODEL_COORDINATE =
            new ProductContributionCoordinate("model.test", "1.0");
    public static final ProductContributionCoordinate PERSISTENCE_COORDINATE =
            new ProductContributionCoordinate("persistence.memory", "1.0");
    public static final ProductContributionCoordinate CONVERSATION_COORDINATE =
            new ProductContributionCoordinate("conversation.memory", "1.0");

    private SdkTestFixtures() {}

    public static ProductProfile profile(
            String productId, Map<ProductCapabilityId, ProductCapabilityRequirement> additions) {
        var requirements = new java.util.LinkedHashMap<ProductCapabilityId, ProductCapabilityRequirement>();
        requirements.put(
                ProductCapabilities.MODEL,
                ProductCapabilityRequirement.required(
                        ProductCapabilities.MODEL, Set.of(MODEL_COORDINATE), ProductProviderSuitability.DEVELOPMENT));
        requirements.put(
                ProductCapabilities.PERSISTENCE,
                ProductCapabilityRequirement.required(
                        ProductCapabilities.PERSISTENCE,
                        Set.of(PERSISTENCE_COORDINATE),
                        ProductProviderSuitability.DEVELOPMENT));
        requirements.put(
                ProductCapabilities.CONVERSATION,
                ProductCapabilityRequirement.required(
                        ProductCapabilities.CONVERSATION,
                        Set.of(CONVERSATION_COORDINATE),
                        ProductProviderSuitability.DEVELOPMENT));
        requirements.putAll(additions);
        return ProductProfile.create(
                new ProductId(productId),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId(productId + "-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                productId + "-chat",
                "1.0.0",
                "Answer the user carefully.",
                new AgentRunBudget(10_000, 10_000, 10_000, 8, 8, 0, "USD", 1_000),
                new AgentRunLimits(8, 0, 1, 30_000, 30_000),
                requirements,
                Set.of(),
                Set.of(),
                Set.of());
    }

    public static List<io.haifa.agent.sdk.product.ProductContribution> baseContributions() {
        return List.of(modelContribution(), persistenceContribution(), conversationContribution());
    }

    public static ModelContribution modelContribution() {
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("test"),
                "1.0",
                new ModelDefinitionId("test-chat"),
                "1.0",
                "test-chat",
                "test-adapter",
                "1.0",
                URI.create("https://model.invalid/v1"),
                new CredentialRef("credential:test"),
                Set.of(ModelCapability.TEXT_CHAT),
                8_192,
                1_024,
                Map.of(),
                Map.of());
        AtomicInteger responses = new AtomicInteger();
        AgentChatModel model = request -> new AgentChatResponse(
                "response-" + responses.incrementAndGet(),
                "test-chat",
                "answer-" + responses.get(),
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
        return new ModelContribution(
                metadata(
                        MODEL_COORDINATE,
                        ProductCapabilities.MODEL,
                        snapshot.configurationDigest(),
                        ProductProviderSuitability.DEVELOPMENT),
                model,
                snapshot);
    }

    public static InMemoryPersistenceContribution persistenceContribution() {
        return new InMemoryPersistenceContribution(metadata(
                PERSISTENCE_COORDINATE,
                ProductCapabilities.PERSISTENCE,
                SdkConfigurationDigest.sha256("persistence-memory-v1"),
                ProductProviderSuitability.DEVELOPMENT));
    }

    public static InMemoryConversationContribution conversationContribution() {
        return new InMemoryConversationContribution(metadata(
                CONVERSATION_COORDINATE,
                ProductCapabilities.CONVERSATION,
                SdkConfigurationDigest.sha256("conversation-memory-v1"),
                ProductProviderSuitability.DEVELOPMENT));
    }

    public static SdkContributionMetadata metadata(
            ProductContributionCoordinate coordinate,
            ProductCapabilityId capability,
            String digest,
            ProductProviderSuitability suitability) {
        return new SdkContributionMetadata(coordinate, capability, digest, suitability, "safe test contribution");
    }
}
