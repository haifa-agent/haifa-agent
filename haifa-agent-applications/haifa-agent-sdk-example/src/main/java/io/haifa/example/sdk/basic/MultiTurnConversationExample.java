package io.haifa.example.sdk.basic;

import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import io.haifa.example.sdk.support.DeterministicExampleSupport;

/** Starts a conversation and submits a second turn using the latest revision. */
public final class MultiTurnConversationExample {
    private MultiTurnConversationExample() {}

    public static void main(String[] args) throws Exception {
        try (var agent = DeterministicExampleSupport.inMemory()) {
            var started = agent.conversations()
                    .start(new StartConversationCommand("basic-start", "Hello", "Introduce Haifa Agent."));
            agent.runs().await(started.activeRunId().orElseThrow());

            var current = agent.conversations().find(started.sessionId()).orElseThrow();
            var continued = agent.conversations()
                    .submit(new SubmitConversationTurnCommand(
                            current.sessionId(),
                            current.revision(),
                            "basic-follow-up",
                            "Summarize that in five words."));
            System.out.println(agent.runs()
                    .await(continued.activeRunId().orElseThrow())
                    .output()
                    .orElseThrow());
        }
    }
}
