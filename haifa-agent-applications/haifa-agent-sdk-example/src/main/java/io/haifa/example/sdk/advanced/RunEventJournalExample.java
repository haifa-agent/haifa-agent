package io.haifa.example.sdk.advanced;

import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.example.sdk.support.DeterministicExampleSupport;

/** Reads the persisted Runtime event journal with an exclusive cursor. */
public final class RunEventJournalExample {
    private RunEventJournalExample() {}

    public static void main(String[] args) throws Exception {
        try (var agent = DeterministicExampleSupport.inMemory()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand("event-start", "Events", "Give a short answer."));
            var runId = conversation.activeRunId().orElseThrow();
            agent.runs().await(runId);
            var page = agent.runs().events(runId, RunEventCursor.beforeFirst(runId), 50);
            page.items().forEach(event -> System.out.println(event.eventType()));
        }
    }
}
