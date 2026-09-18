package io.haifa.agent.sdk;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
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
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.sdk.api.HaifaAgentBuilder;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.contribution.InMemoryConversationContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.internal.InMemoryPersistenceContribution;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class SdkTestFixtures {
    private SdkTestFixtures() {}

    public static ProductProfile profile(String productId) {
        return profile(productId, Set.of(), Set.of());
    }

    public static ProductProfile profile(String productId, Set<String> allowedTools, Set<String> allowedSkills) {
        return ProductProfile.create(
                new ProductId(productId),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId(productId + "-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "Answer the user carefully.",
                new ProductRunProfileRef(productId + "-chat", "1.0.0"),
                new AgentRunBudget(10_000, 10_000, 10_000, 8, 8, 0, "USD", 1_000),
                new AgentRunLimits(8, 0, 1, 30_000, 30_000),
                allowedTools,
                allowedSkills);
    }

    /** Builder with a Model, in-memory Persistence, and in-memory Conversation already assembled. */
    public static HaifaAgentBuilder builder(String productId) {
        return HaifaAgents.builder(profile(productId))
                .model(modelContribution())
                .persistence(persistenceContribution())
                .conversation(conversationContribution())
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()));
    }

    public static ModelContribution modelContribution() {
        ResolvedModelSnapshot snapshot = snapshot();
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
                Map.of(ModelAdapterCoordinate.from(snapshot), model),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));
    }

    public static ResolvedModelSnapshot snapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("test"),
                "1.0",
                new ModelDefinitionId("test-chat"),
                "1.0",
                "test-chat",
                "test-adapter",
                "1.0",
                new ApiStyleId("test-style"),
                "standard",
                URI.create("https://model.invalid/v1"),
                new CredentialRef("credential:test"),
                true,
                Set.of(ModelCapability.TEXT_CHAT),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }

    public static InMemoryPersistenceContribution persistenceContribution() {
        return new InMemoryPersistenceContribution();
    }

    public static InMemoryConversationContribution conversationContribution() {
        return new InMemoryConversationContribution();
    }
}
