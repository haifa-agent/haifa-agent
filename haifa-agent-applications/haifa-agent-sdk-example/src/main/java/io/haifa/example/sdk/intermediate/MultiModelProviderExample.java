package io.haifa.example.sdk.intermediate;

import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.starter.HaifaAgentStarter;
import io.haifa.example.sdk.support.DeterministicExampleSupport;
import java.util.Optional;

/** Registers two trusted providers and selects a non-default model by Run Profile ID. */
public final class MultiModelProviderExample {
    private MultiModelProviderExample() {}

    public static void main(String[] args) throws Exception {
        try (var agent = HaifaAgentStarter.builder()
                .model(
                        DeterministicExampleSupport.model("first-provider"),
                        DeterministicExampleSupport.snapshot("first-provider", "first-model", "first-adapter"))
                .model(
                        DeterministicExampleSupport.model("second-provider"),
                        DeterministicExampleSupport.snapshot("second-provider", "second-model", "second-adapter"))
                .defaultModel("first-model")
                .build()) {
            var first = agent.conversations()
                    .start(new StartConversationCommand("model-default", "Default", "Use the default model."));
            var second = agent.conversations()
                    .start(new StartConversationCommand(
                            "model-selected", "Selected", "Use the selected model.", Optional.of("second-model")));
            System.out.println(agent.runs()
                    .await(first.activeRunId().orElseThrow())
                    .output()
                    .orElseThrow());
            System.out.println(agent.runs()
                    .await(second.activeRunId().orElseThrow())
                    .output()
                    .orElseThrow());
        }
    }
}
