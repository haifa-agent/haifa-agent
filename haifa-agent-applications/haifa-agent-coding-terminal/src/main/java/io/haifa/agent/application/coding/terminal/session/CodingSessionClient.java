package io.haifa.agent.application.coding.terminal.session;

import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingRestoredMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEventListener;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventSubscription;
import java.util.List;

/** Stable product/API boundary consumed by the terminal application. */
public interface CodingSessionClient {
    CodingSessionView create(ProjectId projectId, String firstTurn, String idempotencyKey);

    List<CodingSessionSummary> list(ProjectId projectId, int limit);

    CodingSessionView open(AgentSessionId sessionId);

    CodingSessionView reconcile(AgentSessionId sessionId);

    void submit(AgentSessionId sessionId, String message, String idempotencyKey);

    void steer(AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey);

    void enqueueFollowUp(AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey);

    List<CodingQueuedMessage> restorableMessages(AgentSessionId sessionId, int limit);

    CodingRestoredMessage restore(AgentSessionId sessionId, String followUpId, long revision);

    InteractionResponseReceipt respond(InteractionView interaction, InteractionAction action, String idempotencyKey);

    void cancel(AgentSessionId sessionId, String idempotencyKey);

    RunEventPage events(AgentRunId runId, RunEventCursor after, int limit);

    RunEventCursor acknowledgeCursor(AgentSessionId sessionId, RunEventCursor cursor);

    RunEventSubscription subscribe(AgentRunId runId, RunEventCursor after, AgentRunEventListener listener);

    default List<String> logicalPaths() {
        return List.of();
    }
}
