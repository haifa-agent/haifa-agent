package io.haifa.agent.application.coding.terminal.session;

import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingRestoredMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionQuery;
import io.haifa.agent.application.project.product.coding.CodingSessionService;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEventListener;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventSubscription;
import java.util.List;
import java.util.Objects;

/** Local adapter; terminal code still sees only the product facade and stable Runtime API. */
public final class LocalCodingSessionClient implements CodingSessionClient {
    private final ProjectId projectId;
    private final CodingSessionService sessions;
    private final AgentRuntime runtime;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;

    public LocalCodingSessionClient(
            ProjectId projectId,
            CodingSessionService sessions,
            AgentRuntime runtime,
            IdentifierGenerator identifiers,
            TimeProvider time) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    @Override
    public CodingSessionView create(ProjectId projectId, String firstTurn, String idempotencyKey) {
        requireProject(projectId);
        return scoped(sessions.createSession(projectId, firstTurn, List.of(), idempotencyKey));
    }

    @Override
    public List<CodingSessionSummary> list(ProjectId projectId, int limit) {
        requireProject(projectId);
        return sessions.listSessions(projectId, CodingSessionQuery.firstPage(limit))
                .items();
    }

    @Override
    public CodingSessionView open(AgentSessionId sessionId) {
        return scoped(sessions.openSession(sessionId));
    }

    @Override
    public CodingSessionView reconcile(AgentSessionId sessionId) {
        requireScoped(sessionId);
        return scoped(sessions.reconcileSession(sessionId));
    }

    @Override
    public void submit(AgentSessionId sessionId, String message, String idempotencyKey) {
        requireScoped(sessionId);
        sessions.submitTurn(sessionId, message, List.of(), idempotencyKey);
    }

    @Override
    public void steer(AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey) {
        requireScoped(sessionId);
        sessions.steer(sessionId, activeRunId, message, idempotencyKey);
    }

    @Override
    public void enqueueFollowUp(
            AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey) {
        requireScoped(sessionId);
        sessions.enqueueFollowUp(sessionId, activeRunId, message, List.of(), idempotencyKey);
    }

    @Override
    public List<CodingQueuedMessage> restorableMessages(AgentSessionId sessionId, int limit) {
        requireScoped(sessionId);
        return sessions.listRestorableMessages(sessionId, limit);
    }

    @Override
    public CodingRestoredMessage restore(AgentSessionId sessionId, String followUpId, long revision) {
        requireScoped(sessionId);
        return sessions.restoreQueuedMessage(sessionId, followUpId, revision);
    }

    @Override
    public InteractionResponseReceipt respond(
            InteractionView interaction, InteractionAction action, String idempotencyKey) {
        CodingSessionView current = requireScoped(interaction.sessionId());
        InteractionView pending = current.pendingInteraction()
                .filter(value -> value.requestId().equals(interaction.requestId())
                        && value.runId().equals(interaction.runId())
                        && value.revision() == interaction.revision())
                .orElseThrow(() -> unavailable("Interaction is unavailable"));
        if (!pending.allowedActions().contains(action)) {
            throw unavailable("Interaction action is unavailable");
        }
        return runtime.respond(new InteractionResponseSubmission(
                new InteractionResponseId(identifiers.nextValue()),
                pending.requestId(),
                pending.runId(),
                pending.revision(),
                action,
                List.of(),
                idempotencyKey,
                time.now()));
    }

    @Override
    public void cancel(AgentSessionId sessionId, String idempotencyKey) {
        requireScoped(sessionId);
        sessions.abortActiveRun(sessionId, idempotencyKey);
    }

    @Override
    public RunEventPage events(AgentRunId runId, RunEventCursor after, int limit) {
        requireRunScoped(runId);
        return runtime.events(runId, after, limit);
    }

    @Override
    public RunEventCursor acknowledgeCursor(AgentSessionId sessionId, RunEventCursor cursor) {
        requireScoped(sessionId);
        return sessions.acknowledgeEventCursor(sessionId, cursor);
    }

    @Override
    public RunEventSubscription subscribe(AgentRunId runId, RunEventCursor after, AgentRunEventListener listener) {
        requireRunScoped(runId);
        return runtime.subscribe(runId, after, listener);
    }

    private void requireRunScoped(AgentRunId runId) {
        AgentSessionId sessionId = runtime.view(runId)
                .map(value -> value.sessionId())
                .orElseThrow(() -> unavailable("Run is unavailable"));
        requireScoped(sessionId);
    }

    private CodingSessionView requireScoped(AgentSessionId sessionId) {
        return scoped(sessions.openSession(sessionId));
    }

    private CodingSessionView scoped(CodingSessionView view) {
        if (!view.summary().projectId().equals(projectId)) {
            throw unavailable("Session is unavailable");
        }
        return view;
    }

    private void requireProject(ProjectId requested) {
        if (!projectId.equals(requested)) throw unavailable("Project is unavailable");
    }

    private static ProjectProductException unavailable(String message) {
        return new ProjectProductException("SESSION_NOT_FOUND", message);
    }
}
