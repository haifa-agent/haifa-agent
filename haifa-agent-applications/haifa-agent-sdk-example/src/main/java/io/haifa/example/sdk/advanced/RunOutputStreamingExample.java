package io.haifa.example.sdk.advanced;

import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.example.sdk.support.DeterministicExampleSupport;

/** Consumes transient output while retaining the terminal snapshot as the authoritative answer. */
public final class RunOutputStreamingExample {
    private RunOutputStreamingExample() {}

    public static void main(String[] args) throws Exception {
        try (var agent = DeterministicExampleSupport.inMemory()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand("output-start", "Output", "Give a short answer."));
            var runId = conversation.activeRunId().orElseThrow();
            try (var subscription = agent.runs().subscribeOutput(runId, RunOutputCursor.BEFORE_FIRST, event -> {
                if (event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_DELTA) {
                    System.out.print(event.textDelta());
                }
            })) {
                System.out.println(agent.runs().await(runId).output().orElseThrow());
            }
        }
    }
}
