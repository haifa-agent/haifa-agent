package io.haifa.example.sdk.advanced;

import io.haifa.agent.sdk.conversation.StartConversationCommand;
import java.time.Duration;

/** Queries and waits for a Run without treating a client timeout as a Runtime timeout. */
public final class RunQueryControlExample {
    private RunQueryControlExample() {}

    public static void main(String[] args) throws Exception {
        try (var agent = ExampleAgentFactory.inMemory()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand("query-start", "Query", "Give a short answer."));
            var runId = conversation.activeRunId().orElseThrow();
            var completed = agent.runs().await(runId, Duration.ofSeconds(5));
            System.out.println(completed
                    .orElseGet(() -> agent.runs().handle(runId).snapshot())
                    .status());
        }
    }
}
