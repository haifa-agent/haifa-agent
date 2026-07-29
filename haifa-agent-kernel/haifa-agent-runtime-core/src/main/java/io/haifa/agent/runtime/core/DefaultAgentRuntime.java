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
import io.haifa.agent.policy.api.ApprovalResponder;
import io.haifa.agent.policy.api.ApprovalVerificationService;
import io.haifa.agent.policy.api.PolicyAuthorizationEvidence;
import io.haifa.agent.policy.api.PolicyAuthorizationEvidenceStore;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.runtime.api.AgentRunEventListener;
import io.haifa.agent.runtime.api.AgentRunHandle;
import io.haifa.agent.runtime.api.AgentRunListener;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionResponseReceiptStatus;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.ResumeAgentRunRequest;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventSubscription;
import io.haifa.agent.runtime.api.RunInputReceipt;
import io.haifa.agent.runtime.api.RunInputReceiptStatus;
import io.haifa.agent.runtime.api.RunInputSubmission;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RunOutputSubscription;
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
import io.haifa.agent.runtime.core.event.RuntimeEventFeed;
import io.haifa.agent.runtime.core.event.RuntimeEventSubscriptions;
import io.haifa.agent.runtime.core.execution.AttemptExecutor;
import io.haifa.agent.runtime.core.execution.ExecutionOwnershipPort;
import io.haifa.agent.runtime.core.execution.ExecutionScheduler;
import io.haifa.agent.runtime.core.input.RunInputPort;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.interaction.InteractionRecord;
import io.haifa.agent.runtime.core.interaction.InteractionViewProjector;
import io.haifa.agent.runtime.core.lifecycle.RunAwaiter;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.model.RuntimeModelOutputPublisher;
import io.haifa.agent.runtime.core.retry.PersistenceRetryPolicy;
import io.haifa.agent.runtime.core.retry.RetryExecutor;
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
    private final RuntimeModelOutputPublisher modelOutput;
    private final ExecutionOwnershipPort ownership;
    private final RetryExecutor persistenceRetries;
    private final PersistenceRetryPolicy persistenceRetry;
    private final ApprovalVerificationService approvalVerification;
    private final PolicyAuthorizationEvidenceStore policyAuthorizationEvidence;
    private final PolicyDecisionStore policyDecisions;
    private final RunInputPort runInputs;
    private final RuntimeEventFeed eventFeed;
    private final RuntimeEventSubscriptions eventSubscriptions;
    private final InteractionViewProjector interactionViews = new InteractionViewProjector();

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
            ResumeCoordinator resumeCoordinator,
            RuntimeModelOutputPublisher modelOutput,
            ExecutionOwnershipPort ownership,
            RetryExecutor persistenceRetries,
            PersistenceRetryPolicy persistenceRetry,
            ApprovalVerificationService approvalVerification,
            PolicyAuthorizationEvidenceStore policyAuthorizationEvidence,
            PolicyDecisionStore policyDecisions,
            RunInputPort runInputs,
            RuntimeEventFeed eventFeed,
            RuntimeEventSubscriptions eventSubscriptions) {
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
        this.modelOutput = Objects.requireNonNull(modelOutput);
        this.ownership = Objects.requireNonNull(ownership);
        this.persistenceRetries = Objects.requireNonNull(persistenceRetries);
        this.persistenceRetry = Objects.requireNonNull(persistenceRetry);
        this.approvalVerification = Objects.requireNonNull(approvalVerification);
        this.policyAuthorizationEvidence = Objects.requireNonNull(policyAuthorizationEvidence);
        this.policyDecisions = Objects.requireNonNull(policyDecisions);
        this.runInputs = Objects.requireNonNull(runInputs);
        this.eventFeed = Objects.requireNonNull(eventFeed);
        this.eventSubscriptions = Objects.requireNonNull(eventSubscriptions);
    }

    @Override
    public AgentRunSnapshot start(AgentRunRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        var caller = callers.current();
        String callerScope = callerScope(caller);
        Optional<AgentRunId> existing = persistenceRetries.execute(
                () -> idempotency.findRun(callerScope, "start", request.idempotencyKey()), persistenceRetry.policy());
        if (existing.isPresent()) return snapshot(existing.orElseThrow());

        var bootstrap = bootstrapper.bootstrap(request, caller);
        var definition = bootstrap.definition();
        var profile = bootstrap.profile();
        AgentRun generated = bootstrap.run();
        AgentRunId generatedId = generated.id();
        AtomicBoolean created = new AtomicBoolean();
        AgentRun run = persistenceRetries.execute(
                () -> unitOfWork.execute(() -> {
                    Optional<AgentRunId> raced = idempotency.findRun(callerScope, "start", request.idempotencyKey());
                    if (raced.isPresent()) return requireRun(raced.orElseThrow());
                    state.saveConfiguration(bootstrap.configuration());
                    runs.insert(generated);
                    AgentRunId recorded =
                            idempotency.recordRun(callerScope, "start", request.idempotencyKey(), generatedId);
                    if (!recorded.equals(generatedId)) return requireRun(recorded);
                    created.set(true);
                    appendInitialMessage(generated, request);
                    var event = events.append(
                            generatedId,
                            "run.created",
                            Map.of(
                                    "definitionVersion",
                                    definition.version().toString(),
                                    "version",
                                    generated.version()),
                            time.now());
                    outbox.append(new OutboxMessage(
                            event.eventId(),
                            event.runId(),
                            event.sequence(),
                            event.type(),
                            OutboxMessage.CURRENT_SCHEMA_VERSION,
                            Map.of("profileVersion", profile.version()),
                            event.occurredAt()));
                    transitions.queued(generated);
                    attempts.insert(new AgentRunExecutionAttempt(
                            new ExecutionAttemptId(ids.nextValue()), generatedId, 1, time.now(), Optional.empty()));
                    return generated;
                }),
                persistenceRetry.policy());
        AgentRunSnapshot accepted = AgentRunSnapshot.from(run, state.output(run.id()));
        if (created.get()) submitActive(run);
        return accepted;
    }

    @Override
    public AgentRunSnapshot resume(ResumeAgentRunRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        AgentRun resumable = requireRun(request.runId());
        requireCaller(resumable);
        awaitPreviousAttempt(resumable);
        var caller = callers.current();
        String callerScope = callerScope(caller);
        Optional<AgentRunId> existing = idempotency.findRun(callerScope, "resume", request.idempotencyKey());
        if (existing.isPresent()) return snapshot(existing.orElseThrow());
        AtomicBoolean created = new AtomicBoolean();
        AtomicReference<AgentRunExecutionAttempt> createdAttempt = new AtomicReference<>();
        AgentRun run = unitOfWork.execute(() -> {
            Optional<AgentRunId> raced = idempotency.findRun(callerScope, "resume", request.idempotencyKey());
            if (raced.isPresent()) return requireRun(raced.orElseThrow());
            if (request.expectedRunVersion().isPresent()
                    && request.expectedRunVersion().getAsLong() != resumable.version()) {
                throw new io.haifa.agent.runtime.api.RuntimeContractException(
                        io.haifa.agent.runtime.api.RuntimeErrorCode.RUN_VERSION_CONFLICT,
                        "The expected Run version is stale");
            }
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

    private void awaitPreviousAttempt(AgentRun run) {
        if (run.status() != AgentRunStatus.WAITING_INTERACTION && run.status() != AgentRunStatus.WAITING_APPROVAL) {
            return;
        }
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (attempts.activeFor(run.id()).isPresent()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("previous execution attempt did not pause before resume");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while waiting for the previous execution attempt", exception);
            }
        }
    }

    @Override
    public AgentRunSnapshot respond(InteractionResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        ResponseOutcome outcome = unitOfWork.execute(() -> recordResponse(response));
        AgentRun run = outcome.run();
        if (outcome.toolApproval()) {
            return resume(new ResumeAgentRunRequest(
                    "tool-approval-response:" + response.idempotencyKey(), run.id(), List.of()));
        }
        if (response.type() == InteractionResponseType.REJECT) {
            if (!run.status().isTerminal()) {
                transitions.cancelled(
                        run,
                        new RunTerminationReason("INTERACTION_REJECTED", "Interaction was rejected by the operator"));
            }
            interactions.markResolutionApplied(response.requestId());
            return snapshot(run.id());
        }
        AgentRunSnapshot resumed = resume(
                new ResumeAgentRunRequest("interaction-response:" + response.idempotencyKey(), run.id(), List.of()));
        interactions.markResolutionApplied(response.requestId());
        return resumed;
    }

    @Override
    public InteractionResponseReceipt respond(InteractionResponseSubmission response) {
        Objects.requireNonNull(response, "response must not be null");
        SubmissionOutcome outcome = unitOfWork.execute(() -> recordSubmission(response));
        AgentRun run = outcome.run();
        if (outcome.newlyRecorded()) {
            if (outcome.toolApproval()) {
                resume(new ResumeAgentRunRequest(
                        "tool-approval-response:" + response.idempotencyKey(), run.id(), List.of()));
            } else if (response.action().equals(InteractionAction.REJECT)
                    || response.action().equals(InteractionAction.CANCEL)) {
                if (!run.status().isTerminal()) {
                    transitions.cancelled(
                            run,
                            new RunTerminationReason(
                                    "INTERACTION_REJECTED", "Interaction was rejected by the operator"));
                }
                interactions.markResolutionApplied(response.requestId());
            } else {
                resume(new ResumeAgentRunRequest(
                        "interaction-response:" + response.idempotencyKey(), run.id(), List.of()));
                interactions.markResolutionApplied(response.requestId());
            }
        }
        InteractionRecord current = interactions.record(response.requestId()).orElseThrow();
        AgentRunSnapshot snapshot = snapshot(run.id());
        InteractionResponseReceiptStatus receiptStatus = !outcome.newlyRecorded()
                ? InteractionResponseReceiptStatus.DUPLICATE
                : current.state() == InteractionState.APPLIED
                        ? InteractionResponseReceiptStatus.APPLIED
                        : InteractionResponseReceiptStatus.ACCEPTED_PENDING_APPLICATION;
        return new InteractionResponseReceipt(
                response.responseId(),
                response.requestId(),
                response.runId(),
                receiptStatus,
                current.state(),
                current.revision(),
                snapshot.version());
    }

    private ResponseOutcome recordResponse(InteractionResponse response) {
        AgentRun run = requireRun(response.runId());
        requireCaller(run);
        var caller = callers.current();
        var request = interactions
                .find(response.requestId())
                .orElseThrow(() -> new IllegalArgumentException("unknown interaction request"));
        if (!request.tenant().equals(caller.tenant())) {
            throw new SecurityException("response tenant does not match interaction");
        }
        io.haifa.agent.policy.api.ApprovalVerification approvalResult = verifyApproval(request, caller);
        var resolution = interactions.respond(response, caller, time.now());
        boolean toolApproval =
                resolution.request().target() instanceof io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
        persistRecordedResponse(
                run, request, response, caller, approvalResult, resolution.newlyRecorded(), toolApproval);
        return new ResponseOutcome(run, toolApproval);
    }

    private SubmissionOutcome recordSubmission(InteractionResponseSubmission response) {
        AgentRun run = requireRunForContract(response.runId());
        requireContractCaller(run);
        var caller = callers.current();
        var request = interactions
                .find(response.requestId())
                .orElseThrow(() -> new io.haifa.agent.runtime.api.RuntimeContractException(
                        io.haifa.agent.runtime.api.RuntimeErrorCode.INTERACTION_NOT_FOUND,
                        "The interaction does not exist or is not visible"));
        if (!request.tenant().equals(caller.tenant())) {
            throw new io.haifa.agent.runtime.api.RuntimeContractException(
                    io.haifa.agent.runtime.api.RuntimeErrorCode.INTERACTION_NOT_FOUND,
                    "The interaction does not exist or is not visible");
        }
        var approvalResult = verifyApprovalForContract(request, caller);
        var resolution = interactions.respond(response, caller, time.now());
        InteractionResponse legacy = new InteractionResponse(
                response.responseId(),
                response.requestId(),
                response.runId(),
                legacyType(response.action()),
                response.inputs(),
                response.idempotencyKey(),
                response.respondedAt());
        boolean toolApproval = request.target() instanceof io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
        persistRecordedResponse(run, request, legacy, caller, approvalResult, resolution.newlyRecorded(), toolApproval);
        return new SubmissionOutcome(run, toolApproval, resolution.newlyRecorded());
    }

    private io.haifa.agent.policy.api.ApprovalVerification verifyApproval(
            io.haifa.agent.runtime.core.interaction.InteractionRequest request,
            io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext caller) {
        if (!request.approval()) return null;
        if (request.approvalContext().isPresent()) {
            var verification = approvalVerification.verify(
                    request.approvalContext().orElseThrow(),
                    new ApprovalResponder(caller.tenant(), caller.principal()));
            if (!verification.accepted()) {
                throw new SecurityException("approval verification failed: " + verification.reasonCode());
            }
            return verification;
        }
        if (!request.requester().equals(caller.principal())) {
            throw new SecurityException("legacy approval requires the requester principal");
        }
        return null;
    }

    private io.haifa.agent.policy.api.ApprovalVerification verifyApprovalForContract(
            io.haifa.agent.runtime.core.interaction.InteractionRequest request,
            io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext caller) {
        try {
            return verifyApproval(request, caller);
        } catch (SecurityException exception) {
            io.haifa.agent.runtime.api.RuntimeErrorCode code =
                    exception.getMessage() != null && exception.getMessage().contains("TARGET")
                            ? io.haifa.agent.runtime.api.RuntimeErrorCode.APPROVAL_TARGET_STALE
                            : io.haifa.agent.runtime.api.RuntimeErrorCode.APPROVAL_AUTHORITY_DENIED;
            throw new io.haifa.agent.runtime.api.RuntimeContractException(
                    code, "The approval is no longer authorized for the current target");
        }
    }

    private void persistRecordedResponse(
            AgentRun run,
            io.haifa.agent.runtime.core.interaction.InteractionRequest request,
            InteractionResponse response,
            io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext caller,
            io.haifa.agent.policy.api.ApprovalVerification approvalResult,
            boolean newlyRecorded,
            boolean toolApproval) {
        if (!newlyRecorded) return;
        if (approvalResult != null) {
            interactions.recordApprovalVerification(response.responseId(), approvalResult);
            var securityAt = time.now();
            appendSecurityEvent(
                    run,
                    "approval.authority.verified",
                    Map.of(
                            "requestId",
                            response.requestId().value(),
                            "responseId",
                            response.responseId().value(),
                            "outcome",
                            approvalResult.accepted() ? "ACCEPTED" : "REJECTED",
                            "reasonCode",
                            approvalResult.reasonCode()),
                    securityAt);
            appendSecurityEvent(
                    run,
                    "approval.target.validated",
                    Map.of(
                            "requestId",
                            response.requestId().value(),
                            "responseId",
                            response.responseId().value(),
                            "outcome",
                            approvalResult.accepted() ? "CURRENT" : "REJECTED",
                            "reasonCode",
                            approvalResult.reasonCode()),
                    securityAt);
        }
        if (approvalResult != null && response.type() == InteractionResponseType.APPROVE) {
            var context = request.approvalContext().orElseThrow();
            var decision = policyDecisions
                    .find(context.decisionId())
                    .orElseThrow(() -> new SecurityException("policy decision is unavailable"));
            policyAuthorizationEvidence.save(new PolicyAuthorizationEvidence(
                    context.decisionId(),
                    decision.requestDigest(),
                    context.requester(),
                    new ApprovalResponder(caller.tenant(), caller.principal()),
                    time.now(),
                    context.expiresAt()));
        }
        appendInteractionResponseMessage(
                run, response, toolApproval ? MessageVisibility.INTERNAL : MessageVisibility.AGENT_VISIBLE);
        var event = events.append(
                run.id(),
                request.approval() ? "approval.responded" : "interaction.responded",
                Map.of(
                        "requestId", response.requestId().value(),
                        "responseType", response.type().name(),
                        "responder", caller.principal().principalId()),
                time.now());
        outbox.append(new OutboxMessage(
                event.eventId(),
                event.runId(),
                event.sequence(),
                event.type(),
                OutboxMessage.CURRENT_SCHEMA_VERSION,
                Map.of(
                        "requestId",
                        response.requestId().value(),
                        "responseType",
                        response.type().name()),
                event.occurredAt()));
    }

    private static InteractionResponseType legacyType(InteractionAction action) {
        if (action.equals(InteractionAction.APPROVE)) return InteractionResponseType.APPROVE;
        if (action.equals(InteractionAction.REJECT) || action.equals(InteractionAction.CANCEL)) {
            return InteractionResponseType.REJECT;
        }
        return InteractionResponseType.CLARIFY;
    }

    private void appendSecurityEvent(AgentRun run, String type, Map<String, Object> data, java.time.Instant at) {
        var event = events.append(run.id(), type, data, at);
        outbox.append(new OutboxMessage(
                event.eventId(),
                event.runId(),
                event.sequence(),
                event.type(),
                OutboxMessage.CURRENT_SCHEMA_VERSION,
                event.data(),
                event.occurredAt()));
    }

    private record ResponseOutcome(AgentRun run, boolean toolApproval) {}

    private record SubmissionOutcome(AgentRun run, boolean toolApproval, boolean newlyRecorded) {}

    @Override
    public Optional<InteractionView> pendingInteraction(AgentRunId runId) {
        AgentRun run = requireRunForContract(Objects.requireNonNull(runId, "runId must not be null"));
        requireContractCaller(run);
        return interactions.pendingRecord(runId).map(record -> interactionViews.project(run, record));
    }

    @Override
    public RunInputReceipt submitInput(RunInputSubmission input) {
        Objects.requireNonNull(input, "input must not be null");
        AgentRun run = requireRunForContract(input.runId());
        requireContractCaller(run);
        if (run.status().isTerminal() || run.status() == AgentRunStatus.COMPLETING) {
            throw new io.haifa.agent.runtime.api.RuntimeContractException(
                    io.haifa.agent.runtime.api.RuntimeErrorCode.RUN_STATE_CONFLICT,
                    "The run cannot accept steer input in its current state");
        }
        if (input.expectedRunVersion().isPresent() && input.expectedRunVersion().getAsLong() != run.version()) {
            throw new io.haifa.agent.runtime.api.RuntimeContractException(
                    io.haifa.agent.runtime.api.RuntimeErrorCode.RUN_VERSION_CONFLICT,
                    "The run version is no longer current");
        }
        var caller = callers.current();
        var acceptance = unitOfWork.execute(() -> {
            var accepted = runInputs.accept(input, callerScope(caller), time.now());
            if (accepted.newlyAccepted()) {
                var event = events.append(
                        run.id(),
                        "run.input.accepted",
                        Map.of("inputId", input.inputId().value(), "kind", "steer"),
                        time.now());
                outbox.append(new OutboxMessage(
                        event.eventId(),
                        event.runId(),
                        event.sequence(),
                        event.type(),
                        OutboxMessage.CURRENT_SCHEMA_VERSION,
                        event.data(),
                        event.occurredAt()));
            }
            return accepted;
        });
        RunInputReceiptStatus receiptStatus = acceptance.newlyAccepted()
                ? RunInputReceiptStatus.ACCEPTED
                : acceptance.record().status() == RunInputReceiptStatus.APPLIED
                        ? RunInputReceiptStatus.APPLIED
                        : RunInputReceiptStatus.DUPLICATE;
        return acceptance.record().receipt(receiptStatus);
    }

    /**
     * Converges due interactions for one authorized Run. A scheduler may invoke this repeatedly.
     *
     * <p>Task 02 provides the SQLite due-query and restart-safe implementation.
     */
    public int reconcileExpiredInteractions(AgentRunId runId) {
        AgentRun run = requireRun(Objects.requireNonNull(runId, "runId must not be null"));
        requireCaller(run);
        List<InteractionRecord> due = interactions.due(runId, time.now(), 100);
        int changed = 0;
        for (InteractionRecord record : due) {
            InteractionRecord expired = unitOfWork.execute(() -> {
                InteractionRecord value = interactions.expire(record.request().id(), record.revision(), time.now());
                var event = events.append(
                        run.id(),
                        "interaction.expired",
                        Map.of(
                                "requestId",
                                value.request().id().value(),
                                "outcome",
                                value.request().expirationOutcome().name()),
                        time.now());
                outbox.append(new OutboxMessage(
                        event.eventId(),
                        event.runId(),
                        event.sequence(),
                        event.type(),
                        OutboxMessage.CURRENT_SCHEMA_VERSION,
                        event.data(),
                        event.occurredAt()));
                return value;
            });
            if (expired.state() != InteractionState.EXPIRED) continue;
            changed++;
            switch (record.request().expirationOutcome()) {
                case FAIL_RUN -> {
                    if (!run.status().isTerminal()) {
                        transitions.timedOut(
                                run,
                                new RunTerminationReason(
                                        "INTERACTION_EXPIRED", "Interaction expired without a response"));
                    }
                }
                case CANCEL_RUN -> {
                    if (!run.status().isTerminal()) {
                        transitions.cancelled(
                                run,
                                new RunTerminationReason(
                                        "INTERACTION_EXPIRED", "Interaction expired without a response"));
                    }
                }
                case RETURN_TO_AGENT -> {
                    if (run.status() == AgentRunStatus.WAITING_INTERACTION) {
                        resume(new ResumeAgentRunRequest(
                                "interaction-expired:" + record.request().id().value(), run.id(), List.of()));
                    }
                }
            }
        }
        return changed;
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
        if (command.expectedRunVersion().isPresent()
                && command.expectedRunVersion().getAsLong() != run.version()) {
            throw new io.haifa.agent.runtime.api.RuntimeContractException(
                    io.haifa.agent.runtime.api.RuntimeErrorCode.RUN_VERSION_CONFLICT,
                    "The expected Run version is stale");
        }
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
        var event = events.append(
                run.id(),
                "runtime.command-" + resultStatus.name().toLowerCase(java.util.Locale.ROOT),
                Map.of(
                        "commandId", command.commandId().value(),
                        "commandType", command.type().name(),
                        "operator", caller.principal().principalId()),
                time.now());
        outbox.append(new OutboxMessage(
                event.eventId(),
                event.runId(),
                event.sequence(),
                event.type(),
                OutboxMessage.CURRENT_SCHEMA_VERSION,
                Map.of(
                        "commandId",
                        command.commandId().value(),
                        "commandType",
                        command.type().name()),
                event.occurredAt()));
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
    public Optional<io.haifa.agent.runtime.api.AgentRunViewSnapshot> view(AgentRunId runId) {
        var caller = callers.current();
        return runs.find(Objects.requireNonNull(runId))
                .filter(run -> caller.tenant().equals(run.tenant())
                        && caller.principal().equals(run.principal()))
                .map(run -> new io.haifa.agent.runtime.api.AgentRunViewSnapshot(
                        run.sessionId(), AgentRunSnapshot.from(run, state.output(run.id()))));
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

    @Override
    public List<AgentRunOutputEvent> outputEvents(AgentRunId runId, RunOutputCursor after, int limit) {
        AgentRun run = requireRun(runId);
        requireCaller(run);
        return modelOutput.after(runId, after, limit);
    }

    @Override
    public RunOutputSubscription subscribeOutput(
            AgentRunId runId, RunOutputCursor after, AgentRunOutputListener listener) {
        AgentRun run = requireRun(runId);
        requireCaller(run);
        if (run.status().isTerminal()) {
            return new RunOutputSubscription() {
                @Override
                public boolean closed() {
                    return true;
                }

                @Override
                public void close() {}
            };
        }
        return modelOutput.subscribe(runId, after, listener);
    }

    @Override
    public RunEventPage events(AgentRunId runId, RunEventCursor after, int limit) {
        AgentRun run = requireRunForContract(Objects.requireNonNull(runId, "runId must not be null"));
        requireContractCaller(run);
        return eventFeed.page(runId, after, limit);
    }

    @Override
    public RunEventSubscription subscribe(AgentRunId runId, RunEventCursor after, AgentRunEventListener listener) {
        AgentRun run = requireRunForContract(Objects.requireNonNull(runId, "runId must not be null"));
        requireContractCaller(run);
        return eventSubscriptions.subscribe(runId, after, listener);
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
        if (ownership.stillOwned(active)) {
            throw new IllegalStateException("active execution attempt is still owned by this runtime");
        }
        AgentRunExecutionAttempt replacement = unitOfWork.execute(() -> {
            long expected = active.version();
            active.finish(ExecutionAttemptStatus.ABANDONED, time.now(), Optional.empty());
            attempts.save(active, expected);
            reconcileAbandonedModelSteps(run);
            if (run.status() == AgentRunStatus.RUNNING) transitions.requestPause(run);
            transitions.suspended(run);
            transitions.resumed(run);
            AgentRunExecutionAttempt created = new AgentRunExecutionAttempt(
                    new ExecutionAttemptId(ids.nextValue()),
                    run.id(),
                    attempts.attemptsFor(run.id()).size() + 1,
                    time.now(),
                    resumeCoordinator.latestFor(run));
            attempts.insert(created);
            return created;
        });
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
                .forEach(step -> {
                    step.cancel(time.now());
                    state.appendStep(step);
                });
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

    private void appendInteractionResponseMessage(
            AgentRun run, InteractionResponse response, MessageVisibility visibility) {
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
                visibility,
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

    private AgentRun requireRunForContract(AgentRunId runId) {
        return runs.find(runId)
                .orElseThrow(() -> new io.haifa.agent.runtime.api.RuntimeContractException(
                        io.haifa.agent.runtime.api.RuntimeErrorCode.RUN_NOT_FOUND,
                        "The run does not exist or is not visible"));
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

    private void requireContractCaller(AgentRun run) {
        var caller = callers.current();
        if (!caller.tenant().equals(run.tenant()) || !caller.principal().equals(run.principal())) {
            throw new io.haifa.agent.runtime.api.RuntimeContractException(
                    io.haifa.agent.runtime.api.RuntimeErrorCode.RUN_NOT_FOUND,
                    "The run does not exist or is not visible");
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
