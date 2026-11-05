package io.haifa.agent.application.project.product.coding.presentation;

import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.*;
import io.haifa.agent.application.project.product.coding.client.*;
import io.haifa.agent.core.content.*;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.credential.api.SecretRedactor;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.*;
import io.haifa.coding.api.*;
import io.haifa.coding.api.CodingProtocol.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Engine-owned safe mapping shared by embedded and Host transports. It owns observation only. */
public final class CodingProductBinding implements CodingClient {
    public interface Approvals {
        InteractionResponseReceipt allow(InteractionView pending, String key);

        Optional<CodingApprovalPrompt> prepare(InteractionView pending);

        void reset(AgentSessionId sessionId);
    }

    private final ConnectionInfo connection;
    private final CodingSessionClient legacy;
    private final CodingSessionService sessions;
    private final AgentRuntime runtime;
    private final CodingAuthenticationClient authentication;
    private final SecretRedactor redactor;
    private final Function<AgentSessionId, CodingPresentationSubscription> observe;
    private final Approvals approvals;
    private final Supplier<List<String>> configuration;
    private final Runnable renewal, cleanup;
    private final io.haifa.agent.execution.api.ToolOutputPreviewPublisher previews;
    private final Set<CodingPreviewSubscription> previewObservations = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CodingPresentationSubscription> observations = ConcurrentHashMap.newKeySet();

    public CodingProductBinding(
            ConnectionInfo connection,
            CodingSessionClient legacy,
            CodingSessionService sessions,
            AgentRuntime runtime,
            CodingAuthenticationClient authentication,
            SecretRedactor redactor,
            Function<AgentSessionId, CodingPresentationSubscription> observe,
            Approvals approvals,
            Supplier<List<String>> configuration,
            Runnable renewal,
            Runnable cleanup,
            io.haifa.agent.execution.api.ToolOutputPreviewPublisher previews) {
        this.previews = previews;
        this.connection = connection;
        this.legacy = legacy;
        this.sessions = sessions;
        this.runtime = runtime;
        this.authentication = authentication;
        this.redactor = redactor;
        this.observe = observe;
        this.approvals = approvals;
        this.configuration = configuration;
        this.renewal = renewal;
        this.cleanup = cleanup;
    }

    @Override
    public ConnectionInfo connection() {
        requireOpen();
        return connection;
    }

    private void requireOpen() {
        if (closed.get()) throw new CodingClientException("CLIENT_CLOSED", false);
    }

    private void scope(CommandTarget target) {
        if (target.projectId() != null && !connection.projectId().equals(target.projectId()))
            throw new CodingClientException("PROJECT_SCOPE_MISMATCH", false);
        if (target.sessionId() != null)
            sessions.requireSessionInProject(
                    new AgentSessionId(target.sessionId()), new ProjectId(connection.projectId()));
        if (target.runId() != null) {
            var run = new AgentRunId(target.runId());
            if (legacy.findRun(run).isEmpty()) throw new CodingClientException("RUN_NOT_FOUND", false);
            if (target.sessionId() != null
                    && runtime.view(run)
                            .filter(v -> v.sessionId().value().equals(target.sessionId()))
                            .isEmpty()) throw new CodingClientException("RUN_SCOPE_MISMATCH", false);
        }
    }

    @Override
    public QueryResult query(Query query) {
        requireOpen();
        scope(query.target());
        var result = new QueryData();
        var target = query.target();
        switch (query.operation()) {
            case "sessions" -> {
                var page = sessions.listSessionsForRecovery(
                        new ProjectId(connection.projectId()),
                        new CodingSessionQuery(
                                Optional.ofNullable(query.search()), Optional.empty(), Math.toIntExact(query.limit())));
                result.sessions = page.items().stream().map(this::summary).toList();
                result.truncated = page.hasMore();
            }
            case "run" ->
                result.run = legacy.findRun(run(target)).map(this::runFact).orElse(null);
            case "outcome" ->
                result.outcome = legacy.findOutcome(run(target))
                        .map(o -> new RunOutcome(
                                o.runId().value(), o.protocolStatus().name(), o.diagnosticCodes()))
                        .orElse(null);
            case "models" -> {
                var models = legacy.models();
                result.models =
                        models.stream().limit(query.limit()).map(this::model).toList();
                result.truncated = models.size() > query.limit();
            }
            case "queuedMessages" ->
                result.queued = legacy.restorableMessages(session(target), Math.toIntExact(query.limit())).stream()
                        .map(this::queued)
                        .toList();
            case "shellGet" ->
                result.shells = List.of(shell(legacy.shellExecution(session(target), required(query.executionId()))));
            case "shellList" -> {
                var values = legacy.shellExecutions(session(target));
                result.shells = values.stream().limit(128).map(this::shell).toList();
                result.truncated = values.size() > 128;
            }
            case "workspaces" -> {
                var values = legacy.workspaces();
                result.workspaces = values.stream()
                        .limit(100)
                        .map(w -> new Workspace(
                                w.workspaceRef(), safe(w.safeDisplayName(), 512), w.mode(), w.status(), w.revocable()))
                        .toList();
                result.truncated = values.size() > 100;
            }
            case "logicalPaths" -> result.lines = lines(legacy.logicalPaths());
            case "resources" -> result.lines = lines(legacy.loadedResources());
            case "configuration" -> result.lines = lines(configuration.get());
            case "authentication" -> result.authentication = authentication();
            default -> throw new CodingClientException("QUERY_UNSUPPORTED", false);
        }
        return result.freeze();
    }

    @Override
    public CommandResult execute(CommandRequest request) {
        requireOpen();
        return executeAdmitted(request);
    }
    /** Host-only entry after serialized admission; close may release observations but cannot retract this acceptance. */
    public CommandResult executeAdmitted(CommandRequest request) {
        Command command = request.command();
        CodingProtocolChecks.command(command);
        scope(command.target());
        var result = new CommandData(command.clientRequestId());
        var target = command.target();
        var options = request.options();
        try {
            switch (command.operation()) {
                case "create" -> {
                    var view = sessions.createSession(
                            new ProjectId(connection.projectId()),
                            required(command.text()),
                            List.of(),
                            command.idempotencyKey(),
                            command.optionId() == null
                                    ? CodingSessionCreateOptions.defaults()
                                    : CodingSessionCreateOptions.withInitialModel(command.optionId()));
                    result.session = summary(view.summary());
                    result.runId = view.activeRun().map(r -> r.runId().value()).orElse(null);
                    result.operationId = result.runId;
                }
                case "reconcile" ->
                    result.session = summary(legacy.reconcile(session(target)).summary());
                case "submit" -> {
                    var receipt = sessions.submitTurn(
                            session(target), required(command.text()), List.of(), command.idempotencyKey());
                    result.runId = receipt.runId().value();
                    result.operationId = result.runId;
                }
                case "steer" -> {
                    var receipt = sessions.steer(
                            session(target), run(target), required(command.text()), command.idempotencyKey());
                    result.runId = receipt.runId().value();
                    result.operationId = receipt.inputId().value();
                    if (receipt.status() == RunInputReceiptStatus.REJECTED) result.rejected("INPUT_REJECTED");
                }
                case "followUp" -> {
                    var receipt = sessions.enqueueFollowUp(
                            session(target),
                            run(target),
                            required(command.text()),
                            List.of(),
                            command.idempotencyKey());
                    result.operationId = receipt.followUpId();
                    result.runId =
                            receipt.dispatchedRunId().map(AgentRunId::value).orElse(null);
                    result.queued = new QueuedMessage(
                            receipt.followUpId(),
                            receipt.sessionId().value(),
                            safe(command.text(), 16384),
                            receipt.sequence(),
                            receipt.revision());
                }
                case "restore" -> {
                    var restored = legacy.restore(session(target), required(command.optionId()), revision(target));
                    result.restored = new RestoredMessage(
                            restored.followUpId(),
                            restored.sessionId().value(),
                            safe(restored.message(), 65536),
                            restored.attachments().stream()
                                    .map(a -> new Attachment(a.assetId(), a.mimeType(), a.filename()))
                                    .toList(),
                            restored.revision());
                }
                case "cancel" -> {
                    var receipt = sessions.abortActiveRun(session(target), run(target), command.idempotencyKey());
                    result.runId = target.runId();
                    result.operationId =
                            receipt.map(r -> r.command().commandId().value()).orElse(null);
                    if (receipt.isEmpty()) result.rejected("ACTIVE_RUN_MISMATCH");
                    else approvals.reset(session(target));
                }
                case "rename" ->
                    result.session =
                            summary(legacy.rename(session(target), required(command.text()), revision(target)));
                case "archive" -> result.session = summary(legacy.archive(session(target), revision(target)));
                case "delete" -> legacy.delete(session(target), revision(target));
                case "compact" -> {
                    var value = legacy.compact(session(target), command.text() == null ? "" : command.text());
                    result.compaction = new Compaction(
                            value.summaryReference().orElse(null),
                            value.summaryVersion(),
                            value.sourceMessageCount(),
                            value.estimatedTokens(),
                            value.through().value());
                }
                case "export" -> {
                    var value = legacy.export(session(target), required(command.text()));
                    result.export =
                            new Export(safe(value.logicalPath(), 4096), value.messageCount(), value.schemaVersion());
                }
                case "selectModel" -> {
                    var value = legacy.selectModel(
                            session(target), required(command.optionId()), revision(target), command.idempotencyKey());
                    result.model = new ModelSelection(model(value.model()), value.revision(), value.available());
                }
                case "respond", "allowForSession" -> {
                    var pending = pending(target);
                    InteractionResponseReceipt receipt = command.operation().equals("allowForSession")
                            ? approvals.allow(pending, command.idempotencyKey())
                            : legacy.respond(
                                    pending,
                                    new InteractionAction(required(command.optionId())),
                                    inputs(pending, command, options),
                                    command.idempotencyKey());
                    result.runId = receipt.runId().value();
                    result.operationId = receipt.responseId().value();
                    result.interactionReceipt = new InteractionReceipt(
                            receipt.requestId().value(),
                            receipt.runId().value(),
                            receipt.status().name(),
                            receipt.interactionState().name(),
                            receipt.revision(),
                            receipt.runVersion());
                }
                case "prepareApproval" -> {
                    approvals.prepare(pending(target));
                    try (var view = observe.apply(session(target))) {
                        result.interaction = view.initial().entries().stream()
                                .filter(e -> e.interaction() != null)
                                .findFirst()
                                .orElse(null);
                    }
                    result.operationId = target.interactionId();
                    result.runId = target.runId();
                }
                case "resetConsent" -> approvals.reset(session(target));
                case "shellPlan" -> {
                    var value = legacy.planShell(session(target), required(command.text()), options.includeInContext());
                    result.plan = new ShellPlan(
                            value.token(),
                            value.sessionId().value(),
                            safe(value.safeCommand(), 4096),
                            value.includeInContext(),
                            value.state().name(),
                            value.reasonCode());
                    result.operationId = value.token();
                }
                case "shellStart" -> {
                    var value = legacy.startShell(required(command.executionId()), options.approved());
                    result.shell = shell(value);
                    result.operationId = value.executionId();
                }
                case "shellGet" ->
                    result.shell = shell(legacy.shellExecution(session(target), required(command.executionId())));
                case "shellCancel" ->
                    result.shell = shell(legacy.cancelShell(session(target), required(command.executionId())));
                case "shellDiscard" -> legacy.discardShell(required(command.executionId()));
                case "shellExecute" ->
                    result.shellResult =
                            shellResult(legacy.executeShell(required(command.executionId()), options.approved()));
                case "workspaceRevoke" -> legacy.revokeWorkspace(required(command.optionId()));
                case "resourcesReload" -> legacy.reloadResources();
                case "logout" -> {
                    if (!authentication.logout(required(command.optionId()))) result.rejected("CONNECTION_NOT_FOUND");
                }
                default -> result.rejected("COMMAND_UNSUPPORTED");
            }
        } catch (ProjectProductException rejected) {
            result.rejected(rejected.code());
        } catch (CodingClientException rejected) {
            result.rejected(rejected.code());
        } catch (UnsupportedOperationException unsupported) {
            result.rejected("CAPABILITY_UNSUPPORTED");
        } catch (RuntimeException uncertain) {
            result.disposition = "unknown";
            result.reason = "COMMAND_OUTCOME_UNKNOWN";
        }
        return result.freeze();
    }

    private InteractionView pending(CommandTarget target) {
        var value = legacy.pendingInteraction(run(target))
                .orElseThrow(() -> new CodingClientException("INTERACTION_NOT_FOUND", false));
        if (!value.sessionId().value().equals(target.sessionId())
                || !value.requestId().value().equals(target.interactionId())
                || value.revision() != revision(target))
            throw new CodingClientException("INTERACTION_REVISION_CONFLICT", false);
        return value;
    }

    private List<ContentPart> inputs(InteractionView pending, Command command, CommandOptions options) {
        if (!options.answers().isEmpty()) {
            var questions = pending.clarificationPresentation()
                    .orElseThrow(() -> new CodingClientException("INTERACTION_INPUT_INVALID", false))
                    .questions();
            if (questions.size() != options.answers().size())
                throw new CodingClientException("INTERACTION_INPUT_INVALID", false);
            List<ContentPart> parts = new ArrayList<>();
            for (int i = 0; i < questions.size(); i++) {
                var answer = options.answers().get(i);
                if (!safe(questions.get(i).header(), 32).equals(answer.header())
                        || answer.optionIds().stream()
                                .anyMatch(id -> id.contains(",") || id.contains("\n") || id.contains("\r")))
                    throw new CodingClientException("INTERACTION_INPUT_INVALID", false);
                parts.add(new TextPart(
                        "selected=" + String.join(",", answer.optionIds()) + "\ncustom="
                                + (answer.customText() == null ? "" : answer.customText()),
                        "clarification-answer.v1"));
            }
            return List.copyOf(parts);
        }
        List<ContentPart> parts = new ArrayList<>();
        for (String selected : command.selections()) parts.add(new TextPart(selected, "plain"));
        if (command.text() != null) parts.add(new TextPart(command.text(), "plain"));
        return List.copyOf(parts);
    }

    @Override
    public CodingPresentationSubscription observe(String sessionId) {
        requireOpen();
        sessions.requireSessionInProject(new AgentSessionId(sessionId), new ProjectId(connection.projectId()));
        var source = observe.apply(new AgentSessionId(sessionId));
        observations.add(source);
        if (closed.get()) {
            source.close();
            observations.remove(source);
            throw new CodingClientException("CLIENT_CLOSED", false);
        }
        return new CodingPresentationSubscription() {
            public Snapshot initial() {
                return source.initial();
            }

            public Envelope poll(java.time.Duration timeout) throws InterruptedException {
                return source.poll(timeout);
            }

            public boolean closed() {
                return source.closed();
            }

            public void close() {
                observations.remove(source);
                source.close();
            }
        };
    }

    @Override
    public HistoryPage history(String sessionId, int limit, Long before) {
        requireOpen();
        sessions.requireSessionInProject(new AgentSessionId(sessionId), new ProjectId(connection.projectId()));
        var id = new AgentSessionId(sessionId);
        return CodingPresentationHistory.page(
                before == null ? legacy.history(id, limit) : legacy.history(id, limit, before), redactor);
    }

    @Override
    public boolean renew() {
        requireOpen();
        renewal.run();
        return false;
    }

    @Override
    public CodingPreviewSubscription preview(String sessionId, String runId) {
        requireOpen();
        scope(new CommandTarget(null, sessionId, runId, null, null));
        if (previews == io.haifa.agent.execution.api.ToolOutputPreviewPublisher.noop())
            throw new CodingClientException("PREVIEW_UNAVAILABLE", false);
        var source = new CodingPreviewObservation(previews, new AgentSessionId(sessionId), new AgentRunId(runId));
        previewObservations.add(source);
        if (closed.get()) {
            source.close();
            previewObservations.remove(source);
            throw new CodingClientException("CLIENT_CLOSED", false);
        }
        return new CodingPreviewSubscription() {
            public Preview poll(java.time.Duration timeout) throws InterruptedException {
                return source.poll(timeout);
            }

            public boolean closed() {
                return source.closed();
            }

            public String unavailableReason() {
                return source.unavailableReason();
            }

            public void close() {
                source.close();
                previewObservations.remove(source);
            }
        };
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        previewObservations.forEach(CodingPreviewSubscription::close);
        previewObservations.clear();
        observations.forEach(CodingPresentationSubscription::close);
        observations.clear();
        cleanup.run();
    }

    private String safe(String text, int maximum) {
        return CodingPresentationText.safe(text == null ? "" : text, maximum, redactor);
    }

    private static String required(String text) {
        if (text == null || text.isBlank()) throw new CodingClientException("INVALID_PRODUCT_TARGET", false);
        return text;
    }

    private static AgentSessionId session(CommandTarget target) {
        return new AgentSessionId(required(target.sessionId()));
    }

    private static AgentRunId run(CommandTarget target) {
        return new AgentRunId(required(target.runId()));
    }

    private static long revision(CommandTarget target) {
        if (target.targetRevision() == null) throw new CodingClientException("TARGET_REVISION_REQUIRED", false);
        return target.targetRevision();
    }

    private List<String> lines(List<String> values) {
        return values.stream().limit(100).map(v -> safe(v, 4096)).toList();
    }

    private SessionSummary summary(CodingSessionSummary s) {
        return new SessionSummary(
                s.sessionId().value(),
                s.projectId().value(),
                safe(s.displayName(), 256),
                s.status().name(),
                s.activeRunId().map(AgentRunId::value).orElse(null),
                s.activeRunStatus().map(Enum::name).orElse(null),
                s.queuedCount(),
                s.lastActivityAt().toEpochMilli(),
                s.revision());
    }

    private QueuedMessage queued(CodingQueuedMessage value) {
        return new QueuedMessage(
                value.followUpId(),
                value.sessionId().value(),
                safe(value.summary(), 16384),
                value.sequence(),
                value.revision());
    }

    private ShellExecution shell(CodingShellExecution value) {
        return new ShellExecution(
                value.executionId(),
                value.sessionId().value(),
                value.acceptedAtMillis(),
                value.state().name(),
                value.cancellationRequested(),
                value.result().map(this::shellResult).orElse(null),
                value.errorCode().orElse(null));
    }

    private ShellResult shellResult(CodingShellResult value) {
        String display = safe(value.safeSummary(), Integer.MAX_VALUE);
        return new ShellResult(
                value.processState(),
                value.exitCode().map(Integer::longValue).orElse(null),
                safe(display, 16384),
                value.outputReference().orElse(null),
                value.truncated() || display.codePointCount(0, display.length()) > 16384,
                value.includedInContext());
    }

    private RunFact runFact(AgentRunSnapshot value) {
        var usage = value.usage();
        String output = value.output().map(t -> safe(t, Integer.MAX_VALUE)).orElse(null);
        return new RunFact(
                value.runId().value(),
                value.status().name(),
                value.version(),
                value.updatedAt().toEpochMilli(),
                output == null ? null : safe(output, 16384),
                value.error().map(e -> e.code().wireCode()).orElse(null),
                value.error().map(e -> safe(e.message(), 4096)).orElse(null),
                value.error().flatMap(e -> e.optionalDiagnosticId()).orElse(null),
                value.terminationReason().map(r -> r.code()).orElse(null),
                value.terminationReason().map(r -> safe(r.description(), 4096)).orElse(null),
                new RunUsage(
                        usage.inputTokens(),
                        usage.outputTokens(),
                        usage.cachedInputTokens(),
                        usage.modelCalls(),
                        usage.toolCalls(),
                        usage.childRuns(),
                        usage.costMinorUnits(),
                        usage.wallTimeMillis()),
                value.result()
                        .map(r -> {
                            String display;
                            try {
                                display = new com.fasterxml.jackson.databind.ObjectMapper()
                                        .writeValueAsString(r.structuredOutput());
                            } catch (java.io.IOException failed) {
                                display = "[Structured output unavailable]";
                            }
                            boolean truncated = display.codePointCount(0, display.length()) > 16384
                                    || r.artifacts().size() > 32
                                    || r.warnings().size() > 32;
                            return new RunResult(
                                    r.outcome().name(),
                                    safe(r.summary(), 16384),
                                    r.outputSchemaId(),
                                    r.outputSchemaVersion(),
                                    safe(display, 16384),
                                    r.artifacts().stream()
                                            .limit(32)
                                            .map(a -> new Artifact(
                                                    a.artifactId(),
                                                    a.artifactType(),
                                                    a.version(),
                                                    safe(a.title(), 512)))
                                            .toList(),
                                    r.warnings().stream()
                                            .limit(32)
                                            .map(w -> safe(w, 512))
                                            .toList(),
                                    truncated);
                        })
                        .orElse(null),
                output != null && output.codePointCount(0, output.length()) > 16384);
    }

    private ModelChoice model(CodingModelOption value) {
        var controls = value.controls();
        var response = controls.responseMode();
        var effort = controls.reasoningEffort();
        var state = value.state();
        return new ModelChoice(
                value.id(),
                safe(value.displayName(), 512),
                value.providerId(),
                safe(value.providerDisplayName(), 512),
                value.capabilities().stream().sorted().toList(),
                value.contextWindow(),
                value.maxOutputTokens(),
                state.connection().name(),
                state.bindingAvailability().name(),
                state.runtime().name(),
                state.runScope().name(),
                value.unavailableReason().isBlank() ? null : safe(value.unavailableReason(), 512),
                new ModelControl(
                        response.kind(),
                        response.visible(),
                        response.readOnly(),
                        response.allowedValues().stream().map(Enum::name).toList(),
                        response.recommendedValue().name(),
                        safe(response.effectiveSummary(), 4096)),
                new ModelControl(
                        effort.kind(),
                        effort.visible(),
                        effort.readOnly(),
                        effort.allowedValues().stream().map(Enum::name).toList(),
                        effort.recommendedValue().name(),
                        safe(effort.effectiveSummary(), 4096)),
                value.recommendedPreferences().responseMode().name(),
                value.recommendedPreferences().effort().map(Enum::name).orElse(null),
                value.imageInput()
                        .map(p -> new ImageProfile(
                                p.allowedSources().stream()
                                        .map(Enum::name)
                                        .sorted()
                                        .toList(),
                                p.supportedMediaTypes().stream().sorted().toList(),
                                p.maxImagesPerRequest(),
                                p.maxBytesPerItem(),
                                p.maxTotalBytes(),
                                p.maxUrlCharacters(),
                                p.detailSupported(),
                                p.allowedDetails().stream()
                                        .map(Enum::name)
                                        .sorted()
                                        .toList()))
                        .orElse(null));
    }

    private Authentication authentication() {
        return new Authentication(
                authentication.connectionRequired(),
                authentication.providers().stream()
                        .limit(64)
                        .map(p -> new AuthenticationProvider(
                                p.providerId(),
                                safe(p.displayName(), 512),
                                p.category().name(),
                                p.connected(),
                                p.recommended(),
                                p.current(),
                                p.accountLabel().map(v -> safe(v, 512)).orElse(null),
                                p.planLabel().map(v -> safe(v, 512)).orElse(null),
                                p.methods().stream()
                                        .limit(16)
                                        .map(m -> new AuthenticationMethod(
                                                m.id(),
                                                m.type().name(),
                                                m.available(),
                                                m.unavailableReason()
                                                        .map(v -> safe(v, 512))
                                                        .orElse(null)))
                                        .toList(),
                                p.unavailableReason().map(v -> safe(v, 512)).orElse(null)))
                        .toList(),
                authentication.connections().stream()
                        .limit(64)
                        .map(c -> new AuthenticationConnection(
                                c.connectionId(),
                                c.providerId(),
                                c.method().name(),
                                c.status().name(),
                                safe(c.accountLabel(), 512),
                                c.planType().map(v -> safe(v, 512)).orElse(null),
                                c.resetAtEpochMillis().isPresent()
                                        ? c.resetAtEpochMillis().getAsLong()
                                        : null,
                                c.unofficialLocalCompatibility()))
                        .toList(),
                authentication.localManagementCommand().map(v -> safe(v, 4096)).orElse(null));
    }

    private static final class QueryData {
        List<SessionSummary> sessions = List.of();
        RunFact run;
        RunOutcome outcome;
        List<ModelChoice> models = List.of();
        List<QueuedMessage> queued = List.of();
        List<ShellExecution> shells = List.of();
        List<Workspace> workspaces = List.of();
        List<String> lines = List.of();
        Authentication authentication;
        boolean truncated;

        QueryResult freeze() {
            return new QueryResult(
                    sessions, run, outcome, models, queued, shells, workspaces, lines, authentication, truncated);
        }
    }

    private static final class CommandData {
        final String request;
        String disposition = "accepted", operationId, runId, reason;
        SessionSummary session;
        QueuedMessage queued;
        RestoredMessage restored;
        ShellPlan plan;
        ShellExecution shell;
        ShellResult shellResult;
        Compaction compaction;
        Export export;
        ModelSelection model;
        InteractionReceipt interactionReceipt;
        Entry interaction;

        CommandData(String request) {
            this.request = request;
        }

        void rejected(String code) {
            disposition = "rejected";
            reason = code;
        }

        CommandResult freeze() {
            return new CommandResult(
                    new Receipt(request, disposition, operationId, runId, reason),
                    session,
                    queued,
                    restored,
                    plan,
                    shell,
                    shellResult,
                    compaction,
                    export,
                    model,
                    interactionReceipt,
                    interaction);
        }
    }
}
