package io.haifa.agent.runtime.core;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.RunTerminationReason;
import io.haifa.agent.runtime.api.AgentRunHandle;
import io.haifa.agent.runtime.api.AgentRunListener;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.api.ResumeAgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandArguments;
import io.haifa.agent.runtime.api.RuntimeCommandId;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import io.haifa.agent.runtime.api.RuntimeCommandStatus;
import io.haifa.agent.runtime.api.RuntimeCommandType;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptStatus;
import io.haifa.agent.runtime.core.bootstrap.CallerContextProvider;
import io.haifa.agent.runtime.core.bootstrap.RunBootstrapper;
import io.haifa.agent.runtime.core.checkpoint.ResumeCoordinator;
import io.haifa.agent.runtime.core.control.RunControlService;
import io.haifa.agent.runtime.core.delegation.DelegationPort;
import io.haifa.agent.runtime.core.execution.AttemptExecutor;
import io.haifa.agent.runtime.core.execution.ExecutionScheduler;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.lifecycle.RunAwaiter;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.storage.ExecutionAttemptRepository;
import io.haifa.agent.runtime.core.storage.IdempotencyRepository;
import io.haifa.agent.runtime.core.storage.OutboxMessage;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeOutboxPublisher;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeUnitOfWork;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Snapshot-first, asynchronous pure-Java Runtime implementation. */
public final class DefaultAgentRuntime implements AgentRuntime {
    private final CallerContextProvider callers;
    private final RunBootstrapper bootstrapper;
    private final RunStateRepository runs;
    private final ExecutionAttemptRepository attempts;
    private final RuntimeStateRepository state;
    private final RuntimeEventAppender events;
    private final RuntimeOutboxPublisher outbox;
    private final IdempotencyRepository idempotency;
    private final RuntimeUnitOfWork unitOfWork;
    private final RunTransitionCoordinator transitions;
    private final RunControlService controls;
    private final InteractionPort interactions;
    private final DelegationPort delegations;
    private final AttemptExecutor attemptExecutor;
    private final ExecutionScheduler scheduler;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final RunAwaiter awaiter;
    private final ResumeCoordinator resumeCoordinator;

    public DefaultAgentRuntime(
            CallerContextProvider callers,
            RunBootstrapper bootstrapper,
            RunStateRepository runs,
            ExecutionAttemptRepository attempts,
            RuntimeStateRepository state,
            RuntimeEventAppender events,
            RuntimeOutboxPublisher outbox,
            IdempotencyRepository idempotency,
            RuntimeUnitOfWork unitOfWork,
            RunTransitionCoordinator transitions,
            RunControlService controls,
            InteractionPort interactions,
            DelegationPort delegations,
            AttemptExecutor attemptExecutor,
            ExecutionScheduler scheduler,
            IdentifierGenerator ids,
            TimeProvider time,
            RunAwaiter awaiter,
            ResumeCoordinator resumeCoordinator) {
        this.callers = Objects.requireNonNull(callers);
        this.bootstrapper = Objects.requireNonNull(bootstrapper);
        this.runs = Objects.requireNonNull(runs);
        this.attempts = Objects.requireNonNull(attempts);
        this.state = Objects.requireNonNull(state);
        this.events = Objects.requireNonNull(events);
        this.outbox = Objects.requireNonNull(outbox);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.transitions = Objects.requireNonNull(transitions);
        this.controls = Objects.requireNonNull(controls);
        this.interactions = Objects.requireNonNull(interactions);
        this.delegations = Objects.requireNonNull(delegations);
        this.attemptExecutor = Objects.requireNonNull(attemptExecutor);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
        this.awaiter = Objects.requireNonNull(awaiter);
        this.resumeCoordinator = Objects.requireNonNull(resumeCoordinator);
    }

    @Override
    public AgentRunSnapshot start(AgentRunRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        var caller = callers.current();
        String callerScope = callerScope(caller);
        Optional<AgentRunId> existing = idempotency.findRun(callerScope, "start", request.idempotencyKey());
        if (existing.isPresent()) return snapshot(existing.orElseThrow());

        var bootstrap = bootstrapper.bootstrap(request, caller);
        var definition = bootstrap.definition();
        var profile = bootstrap.profile();
        AgentRun generated = bootstrap.run();
        AgentRunId generatedId = generated.id();
        AtomicBoolean created = new AtomicBoolean();
        AgentRun run = unitOfWork.execute(() -> {
            Optional<AgentRunId> raced = idempotency.findRun(callerScope, "start", request.idempotencyKey());
            if (raced.isPresent()) return requireRun(raced.orElseThrow());
            runs.insert(generated);
            state.saveConfiguration(bootstrap.configuration());
            AgentRunId recorded = idempotency.recordRun(callerScope, "start", request.idempotencyKey(), generatedId);
            if (!recorded.equals(generatedId)) return requireRun(recorded);
            created.set(true);
            appendInitialMessage(generated, request);
            events.append(
                    generatedId,
                    "run.created",
                    Map.of("definitionVersion", definition.version().toString()),
                    time.now());
            outbox.append(new OutboxMessage(
                    ids.nextValue(),
                    generatedId,
                    "run.created",
                    Map.of("profileVersion", profile.version()),
                    time.now()));
            transitions.queued(generated);
            attempts.insert(new AgentRunExecutionAttempt(
                    new ExecutionAttemptId(ids.nextValue()), generatedId, 1, time.now(), Optional.empty()));
            return generated;
        });
        AgentRunSnapshot accepted = AgentRunSnapshot.from(run, state.output(run.id()));
        if (created.get()) submitActive(run);
        return accepted;
    }

    @Override
    public AgentRunSnapshot resume(ResumeAgentRunRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        AgentRun resumable = requireRun(request.runId());
        requireCaller(resumable);
        var caller = callers.current();
        String callerScope = callerScope(caller);
        Optional<AgentRunId> existing = idempotency.findRun(callerScope, "resume", request.idempotencyKey());
        if (existing.isPresent()) return snapshot(existing.orElseThrow());
        AtomicBoolean created = new AtomicBoolean();
        AtomicReference<AgentRunExecutionAttempt> createdAttempt = new AtomicReference<>();
        AgentRun run = unitOfWork.execute(() -> {
            Optional<AgentRunId> raced = idempotency.findRun(callerScope, "resume", request.idempotencyKey());
            if (raced.isPresent()) return requireRun(raced.orElseThrow());
            if (resumable.status() != AgentRunStatus.SUSPENDED
                    && resumable.status() != AgentRunStatus.WAITING_INTERACTION
                    && resumable.status() != AgentRunStatus.WAITING_APPROVAL) {
                throw new IllegalStateException("run is not resumable from " + resumable.status());
            }
            resumeCoordinator.validate(resumable, request, caller);
            request.inputs().forEach(input -> appendResumeMessage(resumable, input));
            var resumedFrom = resumeCoordinator.prepare(resumable, request, caller);
            idempotency.recordRun(callerScope, "resume", request.idempotencyKey(), resumable.id());
            AgentRunExecutionAttempt attempt = new AgentRunExecutionAttempt(
                    new ExecutionAttemptId(ids.nextValue()),
                    resumable.id(),
                    attempts.attemptsFor(resumable.id()).size() + 1,
                    time.now(),
                    resumedFrom);
            attempts.insert(attempt);
            createdAttempt.set(attempt);
            created.set(true);
            return resumable;
        });
        AgentRunSnapshot accepted = snapshot(run.id());
        if (created.get()) {
            AgentRun submittedRun = run;
            scheduler.submit(run.id(), () -> attemptExecutor.execute(submittedRun, createdAttempt.get()));
        }
        return accepted;
    }

    @Override
    public AgentRunSnapshot respond(InteractionResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        AgentRun run = requireRun(response.runId());
        requireCaller(run);
        var caller = callers.current();
        var resolution = interactions.respond(response, caller, time.now());
        if (resolution.newlyRecorded()) {
            unitOfWork.execute(() -> {
                appendInteractionResponseMessage(run, response);
                events.append(
                        run.id(),
                        "interaction.responded",
                        Map.of(
                                "requestId", response.requestId().value(),
                                "responseType", response.type().name(),
                                "operator", caller.principal().principalId()),
                        time.now());
                outbox.append(new OutboxMessage(
                        ids.nextValue(),
                        run.id(),
                        "interaction.responded",
                        Map.of(
                                "requestId",
                                response.requestId().value(),
                                "responseType",
                                response.type().name()),
                        time.now()));
                return null;
            });
        }
        if (resolution.request().target() instanceof io.haifa.agent.runtime.core.interaction.ToolApprovalTarget) {
            return resume(new ResumeAgentRunRequest(
                    "tool-approval-response:" + response.idempotencyKey(), run.id(), List.of()));
        }
        if (response.type() == InteractionResponseType.REJECT) {
            if (!run.status().isTerminal()) {
                transitions.cancelled(
                        run,
                        new RunTerminationReason("INTERACTION_REJECTED", "Interaction was rejected by the operator"));
            }
            return snapshot(run.id());
        }
        return resume(
                new ResumeAgentRunRequest("interaction-response:" + response.idempotencyKey(), run.id(), List.of()));
    }

    @Override
    public RuntimeCommandResult command(RuntimeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        AgentRun run = requireRun(command.runId());
        requireCaller(run);
        var caller = callers.current();
        return unitOfWork.execute(() -> applyCommand(command, run, caller));
    }

    private RuntimeCommandResult applyCommand(
            RuntimeCommand command, AgentRun run, io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext caller) {
        String scope = callerScope(caller) + "|run:" + run.id().value();
        String idempotencyKey = command.idempotencyKey();
        Optional<RuntimeCommandResult> existing = idempotency.findCommandResult(scope, idempotencyKey);
        if (existing.isPresent()) return existing.orElseThrow();
        if (!idempotency.markCommandApplied(scope, idempotencyKey)) {
            throw new IllegalStateException("command was reserved without a durable result");
        }
        RuntimeCommandStatus resultStatus = RuntimeCommandStatus.ACCEPTED;
        switch (command.type()) {
            case PAUSE -> {
                if (run.status() != AgentRunStatus.RUNNING) {
                    resultStatus = RuntimeCommandStatus.REJECTED;
                } else {
                    controls.requestPause(run);
                }
            }
            case CANCEL -> applyCancel(run);
            case TERMINATE_CHILDREN -> delegations.terminateChildren(run);
        }
        events.append(
                run.id(),
                "runtime.command-" + resultStatus.name().toLowerCase(java.util.Locale.ROOT),
                Map.of(
                        "commandId", command.commandId().value(),
                        "commandType", command.type().name(),
                        "operator", caller.principal().principalId()),
                time.now());
        outbox.append(new OutboxMessage(
                ids.nextValue(),
                run.id(),
                "runtime.command-" + resultStatus.name().toLowerCase(java.util.Locale.ROOT),
                Map.of(
                        "commandId",
                        command.commandId().value(),
                        "commandType",
                        command.type().name()),
                time.now()));
        RuntimeCommandResult result = new RuntimeCommandResult(command, resultStatus, snapshot(run.id()));
        idempotency.recordCommandResult(scope, idempotencyKey, result);
        return result;
    }

    @Override
    public Optional<AgentRunSnapshot> find(AgentRunId runId) {
        var caller = callers.current();
        return runs.find(Objects.requireNonNull(runId))
                .filter(run -> caller.tenant().equals(run.tenant())
                        && caller.principal().equals(run.principal()))
                .map(run -> AgentRunSnapshot.from(run, state.output(run.id())));
    }

    @Override
    public AgentRunHandle handle(AgentRunId runId) {
        if (find(runId).isEmpty()) throw new IllegalArgumentException("unknown or invisible run");
        return new Handle(runId);
    }

    @Override
    public void addListener(AgentRunListener listener) {
        transitions.addListener(listener);
    }

    /** Reclaims a run whose physical executor disappeared after durable checkpointing. */
    public AgentRunSnapshot recover(AgentRunId runId) {
        AgentRun run = requireRun(runId);
        requireCaller(run);
        if (run.status() != AgentRunStatus.RUNNING && run.status() != AgentRunStatus.SUSPENDING) {
            throw new IllegalStateException("only an executing run can be recovered");
        }
        AgentRunExecutionAttempt active = attempts.activeFor(runId)
                .orElseThrow(() -> new IllegalStateException("run has no active attempt to recover"));
        long expected = active.version();
        active.finish(ExecutionAttemptStatus.ABANDONED, time.now(), Optional.empty());
        attempts.save(active, expected);
        reconcileAbandonedModelSteps(run);
        if (run.status() == AgentRunStatus.RUNNING) transitions.requestPause(run);
        transitions.suspended(run);
        transitions.resumed(run);
        AgentRunExecutionAttempt replacement = new AgentRunExecutionAttempt(
                new ExecutionAttemptId(ids.nextValue()),
                run.id(),
                attempts.attemptsFor(run.id()).size() + 1,
                time.now(),
                resumeCoordinator.latestFor(run));
        attempts.insert(replacement);
        AgentRunSnapshot accepted = snapshot(run.id());
        scheduler.submit(run.id(), () -> attemptExecutor.execute(run, replacement));
        return accepted;
    }

    private void reconcileAbandonedModelSteps(AgentRun run) {
        var toolStepIds = state.toolCalls(run.id()).stream()
                .map(call -> call.stepId())
                .collect(java.util.stream.Collectors.toSet());
        state.steps(run.id()).stream()
                .filter(step -> step.status() == io.haifa.agent.core.step.AgentStepStatus.RUNNING
                        || step.status() == io.haifa.agent.core.step.AgentStepStatus.WAITING)
                .filter(step -> !toolStepIds.contains(step.id()))
                .forEach(step -> step.cancel(time.now()));
    }

    private void applyCancel(AgentRun run) {
        if (run.status().isTerminal()) return;
        delegations.terminateChildren(run);
        events.append(run.id(), "children.termination-requested", Map.of("reason", "PARENT_CANCELLED"), time.now());
        if (run.status() == AgentRunStatus.RUNNING || run.status() == AgentRunStatus.SUSPENDING) {
            controls.requestCancel(run);
            return;
        }
        transitions.cancelled(run, new RunTerminationReason("USER_CANCELLED", "Cancellation requested"));
        attempts.activeFor(run.id()).ifPresent(attempt -> {
            long expected = attempt.version();
            attempt.finish(ExecutionAttemptStatus.CANCELLED, time.now(), Optional.empty());
            attempts.save(attempt, expected);
        });
    }

    private void submitActive(AgentRun run) {
        AgentRunExecutionAttempt attempt = attempts.activeFor(run.id()).orElseThrow();
        scheduler.submit(run.id(), () -> attemptExecutor.execute(run, attempt));
    }

    private void appendInitialMessage(AgentRun run, AgentRunRequest request) {
        List<ContentPart> contents = new ArrayList<>();
        contents.add(new TextPart(request.objective(), "plain"));
        contents.addAll(request.inputs());
        state.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId(ids.nextValue()),
                request.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                MessageRole.USER,
                MessageStatus.COMPLETED,
                MessageVisibility.USER_VISIBLE,
                contents,
                Map.of(),
                time.now()));
    }

    private void appendResumeMessage(AgentRun run, ContentPart input) {
        state.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId(ids.nextValue()),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                MessageRole.USER,
                MessageStatus.COMPLETED,
                MessageVisibility.USER_VISIBLE,
                List.of(input),
                Map.of("resume", true),
                time.now()));
    }

    private void appendInteractionResponseMessage(AgentRun run, InteractionResponse response) {
        List<ContentPart> contents = response.inputs().isEmpty()
                ? List.of(new TextPart("Interaction response: " + response.type(), "plain"))
                : response.inputs();
        state.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId(ids.nextValue()),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                MessageRole.USER,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                contents,
                Map.of(
                        "interactionRequestId", response.requestId().value(),
                        "interactionResponseId", response.responseId().value(),
                        "interactionResponseType", response.type().name()),
                time.now()));
    }

    private AgentRun requireRun(AgentRunId runId) {
        return runs.find(runId).orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId.value()));
    }

    private AgentRunSnapshot snapshot(AgentRunId runId) {
        AgentRun run = requireRun(runId);
        requireCaller(run);
        return AgentRunSnapshot.from(run, state.output(runId));
    }

    private void requireCaller(AgentRun run) {
        var caller = callers.current();
        if (!caller.tenant().equals(run.tenant()) || !caller.principal().equals(run.principal())) {
            throw new SecurityException("caller does not own the run");
        }
    }

    private static String callerScope(io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext caller) {
        return caller.tenant().tenantId() + "|" + caller.principal().principalType() + "|"
                + caller.principal().principalId();
    }

    private static String callerScope(AgentRun run) {
        return run.tenant().tenantId() + "|" + run.principal().principalType() + "|"
                + run.principal().principalId();
    }

    private final class Handle implements AgentRunHandle {
        private final AgentRunId runId;

        private Handle(AgentRunId runId) {
            this.runId = runId;
        }

        @Override
        public AgentRunId runId() {
            return runId;
        }

        @Override
        public AgentRunStatus status() {
            return snapshot().status();
        }

        @Override
        public AgentRunSnapshot snapshot() {
            return DefaultAgentRuntime.this.snapshot(runId);
        }

        @Override
        public AgentRunSnapshot awaitCompletion() throws InterruptedException {
            return awaiter.await(runId, this::snapshot, value -> value.status().isTerminal());
        }

        @Override
        public Optional<AgentRunSnapshot> awaitCompletion(Duration timeout) throws InterruptedException {
            return awaiter.await(
                    runId, timeout, this::snapshot, value -> value.status().isTerminal());
        }

        @Override
        public RuntimeCommandResult pause() {
            return command(RuntimeCommandType.PAUSE);
        }

        @Override
        public RuntimeCommandResult cancel() {
            return command(RuntimeCommandType.CANCEL);
        }

        private RuntimeCommandResult command(RuntimeCommandType type) {
            return DefaultAgentRuntime.this.command(new RuntimeCommand(
                    new RuntimeCommandId(ids.nextValue()),
                    runId,
                    type,
                    RuntimeCommandArguments.NONE,
                    ids.nextValue(),
                    time.now()));
        }
    }
}
