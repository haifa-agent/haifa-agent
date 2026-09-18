package io.haifa.example.sdk.support;

import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.starter.HaifaAgentStarter;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Deterministic, network-free support shared only by this unpublished example module. */
public final class DeterministicExampleSupport {
    private DeterministicExampleSupport() {}

    public static HaifaAgent inMemory() {
        return HaifaAgentStarter.builder()
                .model(model("example-answer"), snapshot())
                .build();
    }

    public static AgentChatModel model(String answer) {
        return request -> new AgentChatResponse(
                "example-response",
                request.model().providerModelId(),
                answer,
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(4, 4),
                "",
                Map.of());
    }

    @SafeVarargs
    public static AgentChatModel scripted(Function<AgentChatRequest, AgentChatResponse>... steps) {
        List<Function<AgentChatRequest, AgentChatResponse>> script = List.of(steps);
        if (script.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
        var index = new AtomicInteger();
        return request -> {
            int current = index.getAndIncrement();
            if (current >= script.size()) {
                throw new IllegalStateException("deterministic model script is exhausted");
            }
            return script.get(current).apply(request);
        };
    }

    public static ResolvedModelSnapshot snapshot() {
        return snapshot("example", "example-chat", "example-adapter");
    }

    public static ResolvedModelSnapshot snapshot(String providerId, String modelId, String adapterType) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(providerId),
                "1.0.0",
                new ModelDefinitionId(modelId),
                "1.0.0",
                modelId,
                adapterType,
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://EXAMPLE_MODEL_KEY"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.STRUCTURED_OUTPUT),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }
}
