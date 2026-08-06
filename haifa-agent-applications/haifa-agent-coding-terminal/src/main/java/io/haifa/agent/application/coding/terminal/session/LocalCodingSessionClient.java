package io.haifa.agent.application.coding.terminal.session;

import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingCompactionResult;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingModelSelection;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingRestoredMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionExportResult;
import io.haifa.agent.application.project.product.coding.CodingSessionExportService;
import io.haifa.agent.application.project.product.coding.CodingSessionQuery;
import io.haifa.agent.application.project.product.coding.CodingSessionService;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.CodingShellPlan;
import io.haifa.agent.application.project.product.coding.CodingShellResult;
import io.haifa.agent.application.project.product.coding.CodingShellService;
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
import java.util.function.Supplier;

/** Local adapter; terminal code still sees only the product facade and stable Runtime API. */
public final class LocalCodingSessionClient implements CodingSessionClient {
    private final ProjectId projectId;
    private final CodingSessionService sessions;
    private final AgentRuntime runtime;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;
    private final Supplier<List<String>> logicalPaths;
    private final Supplier<List<String>> loadedResources;
    private final Supplier<List<String>> resourceReloader;
    private final java.util.Optional<CodingShellService> shell;
    private final java.util.Optional<CodingSessionExportService> exporter;

    public LocalCodingSessionClient(
            ProjectId projectId,
            CodingSessionService sessions,
            AgentRuntime runtime,
            IdentifierGenerator identifiers,
            TimeProvider time) {
        this(
                projectId,
                sessions,
                runtime,
                identifiers,
                time,
                List::of,
                List::of,
                List::of,
                java.util.Optional.empty(),
                null);
    }

    public LocalCodingSessionClient(
            ProjectId projectId,
            CodingSessionService sessions,
            AgentRuntime runtime,
            IdentifierGenerator identifiers,
            TimeProvider time,
            Supplier<List<String>> logicalPaths) {
        this(
                projectId,
                sessions,
                runtime,
                identifiers,
                time,
                logicalPaths,
                List::of,
                List::of,
                java.util.Optional.empty(),
                null);
    }

    public LocalCodingSessionClient(
            ProjectId projectId,
            CodingSessionService sessions,
            AgentRuntime runtime,
            IdentifierGenerator identifiers,
            TimeProvider time,
            Supplier<List<String>> logicalPaths,
            Supplier<List<String>> loadedResources,
            Supplier<List<String>> resourceReloader,
            java.util.Optional<CodingShellService> shell,
            CodingSessionExportService exporter) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.logicalPaths = Objects.requireNonNull(logicalPaths, "logicalPaths must not be null");
        this.loadedResources = Objects.requireNonNull(loadedResources, "loadedResources must not be null");
        this.resourceReloader = Objects.requireNonNull(resourceReloader, "resourceReloader must not be null");
        this.shell = Objects.requireNonNull(shell, "shell must not be null");
        this.exporter = java.util.Optional.ofNullable(exporter);
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
    public List<CodingSessionSummary> search(ProjectId projectId, String text, int limit) {
        requireProject(projectId);
        return sessions.listSessions(
                        projectId,
                        new CodingSessionQuery(
                                java.util.Optional.ofNullable(text).filter(value -> !value.isBlank()),
                                java.util.Optional.empty(),
                                limit))
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
    public List<CodingModelOption> models() {
        return sessions.availableModels();
    }

    @Override
    public CodingModelSelection selectModel(
            AgentSessionId sessionId, String modelId, long expectedRevision, String idempotencyKey) {
        requireScoped(sessionId);
        return sessions.selectModel(sessionId, modelId, expectedRevision, idempotencyKey);
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
    public java.util.Optional<InteractionView> pendingInteraction(AgentRunId runId) {
        requireRunScoped(runId);
        return runtime.pendingInteraction(runId);
    }

    @Override
    public InteractionResponseReceipt respond(
            InteractionView interaction, InteractionAction action, String idempotencyKey) {
        requireRunScoped(interaction.runId());
        InteractionView pending = runtime.pendingInteraction(interaction.runId())
                .filter(value -> value.requestId().equals(interaction.requestId())
                        && value.runId().equals(interaction.runId())
                        && value.sessionId().equals(interaction.sessionId())
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
    public CodingSessionSummary rename(AgentSessionId sessionId, String displayName, long expectedRevision) {
        requireScoped(sessionId);
        return sessions.renameSession(sessionId, displayName, expectedRevision);
    }

    @Override
    public CodingSessionSummary archive(AgentSessionId sessionId, long expectedRevision) {
        requireScoped(sessionId);
        return sessions.archiveSession(sessionId, expectedRevision);
    }

    @Override
    public void delete(AgentSessionId sessionId, long expectedRevision) {
        requireScoped(sessionId);
        sessions.deleteSession(sessionId, expectedRevision);
    }

    @Override
    public CodingCompactionResult compact(AgentSessionId sessionId, String safeInstruction) {
        requireScoped(sessionId);
        return sessions.compactSession(sessionId, safeInstruction);
    }

    @Override
    public CodingShellPlan planShell(AgentSessionId sessionId, String command, boolean includeInContext) {
        requireScoped(sessionId);
        return shell.orElseThrow(() -> new UnsupportedOperationException("Shell execution is unavailable"))
                .plan(sessionId, command, includeInContext);
    }

    @Override
    public CodingShellResult executeShell(String token, boolean approved) {
        return shell.orElseThrow(() -> new UnsupportedOperationException("Shell execution is unavailable"))
                .execute(token, approved);
    }

    @Override
    public void discardShell(String token) {
        shell.orElseThrow(() -> new UnsupportedOperationException("Shell execution is unavailable"))
                .discard(token);
    }

    @Override
    public CodingSessionExportResult export(AgentSessionId sessionId, String logicalDestination) {
        requireScoped(sessionId);
        return exporter.orElseThrow(() -> new UnsupportedOperationException("Session export is unavailable"))
                .export(sessionId, logicalDestination);
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

    @Override
    public io.haifa.agent.runtime.api.RunOutputSubscription subscribeOutput(
            AgentRunId runId,
            io.haifa.agent.runtime.api.RunOutputCursor after,
            io.haifa.agent.runtime.api.AgentRunOutputListener listener) {
        requireRunScoped(runId);
        return runtime.subscribeOutput(runId, after, listener);
    }

    @Override
    public List<String> logicalPaths() {
        return List.copyOf(logicalPaths.get());
    }

    @Override
    public List<String> loadedResources() {
        return List.copyOf(loadedResources.get());
    }

    @Override
    public List<String> reloadResources() {
        return List.copyOf(resourceReloader.get());
    }

    private void requireRunScoped(AgentRunId runId) {
        AgentSessionId sessionId = runtime.view(runId)
                .map(value -> value.sessionId())
                .orElseThrow(() -> unavailable("Run is unavailable"));
        sessions.requireSessionInProject(sessionId, projectId);
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
