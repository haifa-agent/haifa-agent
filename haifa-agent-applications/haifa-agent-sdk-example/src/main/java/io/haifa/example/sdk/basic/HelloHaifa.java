package io.haifa.example.sdk.basic;

import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.starter.HaifaAgentStarter;

/** Minimal real-provider Quickstart; requires {@code DEEPSEEK_API_KEY}. */
public final class HelloHaifa {
    private HelloHaifa() {}

    public static void main(String[] arguments) throws Exception {
        try (HaifaAgent agent = HaifaAgentStarter.create()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand(
                            "hello-1", "Hello Haifa", "Introduce Haifa Agent in one short sentence."));
            var completed = agent.runs().await(conversation.activeRunId().orElseThrow());
            System.out.println(completed.output().orElseThrow());
        }
    }
}
