package io.haifa.example.sdk.advanced;

import io.haifa.agent.sdk.conversation.StartConversationCommand;

/** Demonstrates caller-scoped command idempotency. */
public final class IdempotencyAndRevisionExample {
    private IdempotencyAndRevisionExample() {}

    public static void main(String[] args) {
        try (var agent = ExampleAgentFactory.inMemory()) {
            var command = new StartConversationCommand("same-intent", "Trip", "Introduce Hangzhou.");
            var first = agent.conversations().start(command);
            var retry = agent.conversations().start(command);
            System.out.println(first.sessionId().equals(retry.sessionId()));
        }
    }
}
