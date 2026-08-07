package io.haifa.agent.application.coding.terminal.session;

import io.haifa.agent.application.project.product.coding.CodingCompactionResult;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingModelSelection;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingRestoredMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionExportResult;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryPage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.CodingShellPlan;
import io.haifa.agent.application.project.product.coding.CodingShellResult;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEventListener;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventSubscription;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RunOutputSubscription;
import java.util.List;
import java.util.Optional;

/** Stable product/API boundary consumed by the terminal application. */
public interface CodingSessionClient {
    CodingSessionView create(ProjectId projectId, String firstTurn, String idempotencyKey);

    List<CodingSessionSummary> list(ProjectId projectId, int limit);

    default List<CodingSessionSummary> search(ProjectId projectId, String text, int limit) {
        return list(projectId, limit);
    }

    CodingSessionView open(AgentSessionId sessionId);

    CodingSessionView reconcile(AgentSessionId sessionId);

    default CodingSessionHistoryPage history(AgentSessionId sessionId, int limit) {
        return CodingSessionHistoryPage.empty(sessionId);
    }

    default List<CodingModelOption> models() {
        return List.of();
    }

    default CodingModelSelection selectModel(
            AgentSessionId sessionId, String modelId, long expectedRevision, String idempotencyKey) {
        throw new UnsupportedOperationException("Model selection is unavailable");
    }

    void submit(AgentSessionId sessionId, String message, String idempotencyKey);

    void steer(AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey);

    void enqueueFollowUp(AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey);

    List<CodingQueuedMessage> restorableMessages(AgentSessionId sessionId, int limit);

    CodingRestoredMessage restore(AgentSessionId sessionId, String followUpId, long revision);

    Optional<InteractionView> pendingInteraction(AgentRunId runId);

    InteractionResponseReceipt respond(InteractionView interaction, InteractionAction action, String idempotencyKey);

    void cancel(AgentSessionId sessionId, String idempotencyKey);

    default CodingSessionSummary rename(AgentSessionId sessionId, String displayName, long expectedRevision) {
        throw new UnsupportedOperationException("Session rename is unavailable");
    }

    default CodingSessionSummary archive(AgentSessionId sessionId, long expectedRevision) {
        throw new UnsupportedOperationException("Session archive is unavailable");
    }

    default void delete(AgentSessionId sessionId, long expectedRevision) {
        throw new UnsupportedOperationException("Session delete is unavailable");
    }

    default CodingCompactionResult compact(AgentSessionId sessionId, String safeInstruction) {
        throw new UnsupportedOperationException("Session compaction is unavailable");
    }

    default CodingShellPlan planShell(AgentSessionId sessionId, String command, boolean includeInContext) {
        throw new UnsupportedOperationException("Shell execution is unavailable");
    }

    default CodingShellResult executeShell(String token, boolean approved) {
        throw new UnsupportedOperationException("Shell execution is unavailable");
    }

    default void discardShell(String token) {}

    default CodingSessionExportResult export(AgentSessionId sessionId, String logicalDestination) {
        throw new UnsupportedOperationException("Session export is unavailable");
    }

    RunEventPage events(AgentRunId runId, RunEventCursor after, int limit);

    RunEventCursor acknowledgeCursor(AgentSessionId sessionId, RunEventCursor cursor);

    RunEventSubscription subscribe(AgentRunId runId, RunEventCursor after, AgentRunEventListener listener);

    default RunOutputSubscription subscribeOutput(
            AgentRunId runId, RunOutputCursor after, AgentRunOutputListener listener) {
        return new RunOutputSubscription() {
            @Override
            public boolean closed() {
                return true;
            }

            @Override
            public void close() {}
        };
    }

    default List<String> logicalPaths() {
        return List.of();
    }

    default List<String> loadedResources() {
        return List.of("Loaded resources: none");
    }

    default List<String> reloadResources() {
        return loadedResources();
    }
}
