package io.haifa.example.consumer.plain;

import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.starter.HaifaAgentStarter;

/**
 * Complete pure Java consumer application demonstrating real-time token streaming.
 */
public final class PureJavaStreamingApplication {
    private PureJavaStreamingApplication() {}

    public static void main(String[] arguments) throws Exception {
        try (var agent = HaifaAgentStarter.builder()
                .name("standalone-streaming-agent")
                .instructions("You are a helpful assistant. Reply concisely in one or two sentences.")
                .build()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand(
                            "streaming-" + System.currentTimeMillis(),
                            "StreamingDemo",
                            "Explain why the sky is blue in two sentences."));
            var runId = conversation.activeRunId().orElseThrow();

            System.out.println("Streaming response:");
            try (var subscription = agent.runs().subscribeOutput(runId, RunOutputCursor.BEFORE_FIRST, event -> {
                if (event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_DELTA) {
                    System.out.print(event.textDelta());
                    System.out.flush();
                }
            })) {
                agent.runs().await(runId);
                System.out.println();
            }
        }
    }
}
