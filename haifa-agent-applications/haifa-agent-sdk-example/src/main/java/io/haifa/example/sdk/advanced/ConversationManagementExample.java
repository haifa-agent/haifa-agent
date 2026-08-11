package io.haifa.example.sdk.advanced;

import io.haifa.agent.sdk.conversation.RenameConversationCommand;
import io.haifa.agent.sdk.conversation.StartConversationCommand;

/** Creates, waits for, and renames one durable conversation aggregate. */
public final class ConversationManagementExample {
    private ConversationManagementExample() {}

    public static void main(String[] args) throws Exception {
        try (var agent = ExampleAgentFactory.inMemory()) {
            var started = agent.conversations()
                    .start(new StartConversationCommand("conversation-start", "Trip", "Introduce Hangzhou."));
            agent.runs().await(started.activeRunId().orElseThrow());
            var current = agent.conversations().find(started.sessionId()).orElseThrow();
            var renamed = agent.conversations()
                    .rename(new RenameConversationCommand(
                            current.sessionId(), current.revision(), "conversation-rename", "Hangzhou trip"));
            System.out.println(renamed.displayName());
        }
    }
}
