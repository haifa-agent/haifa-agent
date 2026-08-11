package io.haifa.example.sdk.basic;

import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.example.sdk.support.DeterministicExampleSupport;
import java.util.List;

/** Assembles once, runs multiple Conversations, and closes the Agent idempotently. */
public final class AgentReuseLifecycleExample {
    private AgentReuseLifecycleExample() {}

    public static void main(String[] arguments) throws Exception {
        var agent = DeterministicExampleSupport.inMemory();
        try {
            List<String> questions = List.of("Describe Hangzhou.", "Describe Shanghai.");
            for (int index = 0; index < questions.size(); index++) {
                var conversation = agent.conversations()
                        .start(new StartConversationCommand(
                                "reuse-" + index, "Conversation " + (index + 1), questions.get(index)));
                var completed = agent.runs().await(conversation.activeRunId().orElseThrow());
                System.out.println(completed.output().orElseThrow());
            }
        } finally {
            agent.close();
            agent.close();
        }

        try {
            agent.runs();
        } catch (HaifaAgentException exception) {
            System.out.println("closedCode=" + exception.code());
        }
    }
}
