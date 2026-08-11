package io.haifa.example.sdk.advanced;

import io.haifa.agent.model.api.AgentChatModel;
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

final class ExampleAgentFactory {
    private ExampleAgentFactory() {}

    static HaifaAgent inMemory() {
        return HaifaAgentStarter.builder()
                .model(model("example-answer"), snapshot())
                .build();
    }

    static AgentChatModel model(String answer) {
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

    static ResolvedModelSnapshot snapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("example"),
                "1.0.0",
                new ModelDefinitionId("example-chat"),
                "1.0.0",
                "example-chat",
                "example-adapter",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://EXAMPLE_MODEL_KEY"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }
}
