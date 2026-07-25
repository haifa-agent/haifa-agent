package io.haifa.agent.core.tool;

import static io.haifa.agent.core.support.DomainValues.requireText;

import io.haifa.agent.core.persistence.DomainReconstitution;
import io.haifa.agent.core.persistence.DomainReconstitutionException;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStepId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One actual tool invocation, distinct from a capability ToolDefinition. */
public final class ToolCall {

    private final ToolCallId id;
    private final AgentRunId runId;
    private final AgentStepId stepId;
    private final ProviderToolCallCorrelationId providerCorrelationId;
    private final RuntimeIdempotencyKey idempotencyKey;
    private final String toolName;
    private final String toolVersion;
    private final ToolArguments arguments;
    private final Instant requestedAt;
    private ToolCallStatus status = ToolCallStatus.REQUESTED;
    private Instant startedAt;
    private Instant completedAt;
    private ToolResult result;
    private ToolExecutionError error;
    private long version;

    public ToolCall(
            ToolCallId id,
            AgentRunId runId,
            AgentStepId stepId,
            ProviderToolCallCorrelationId providerCorrelationId,
            RuntimeIdempotencyKey idempotencyKey,
            String toolName,
            String toolVersion,
            ToolArguments arguments,
            Instant requestedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.stepId = Objects.requireNonNull(stepId, "stepId must not be null");
        this.providerCorrelationId =
                Objects.requireNonNull(providerCorrelationId, "providerCorrelationId must not be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        this.toolName = requireText(toolName, "toolName");
        this.toolVersion = requireText(toolVersion, "toolVersion");
        this.arguments = Objects.requireNonNull(arguments, "arguments must not be null");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
    }

    public static ToolCall reconstitute(ToolCallPersistenceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        DomainReconstitution.requireSupportedVersion(snapshot.schemaVersion(), "ToolCall");
        ToolCallStatus restoredStatus =
                DomainReconstitution.enumValue(ToolCallStatus.class, snapshot.status(), "tool call status");
        DomainReconstitution.requireVersion(snapshot.version(), "tool call");
        validatePersistedState(snapshot, restoredStatus);
        try {
            ToolCall call = new ToolCall(
                    snapshot.id(),
                    snapshot.runId(),
                    snapshot.stepId(),
                    snapshot.providerCorrelationId(),
                    snapshot.idempotencyKey(),
                    snapshot.toolName(),
                    snapshot.toolVersion(),
                    snapshot.arguments(),
                    snapshot.requestedAt());
            call.status = restoredStatus;
            call.startedAt = snapshot.startedAt();
            call.completedAt = snapshot.completedAt();
            call.result = snapshot.result();
            call.error = snapshot.error();
            call.version = snapshot.version();
            return call;
        } catch (DomainReconstitutionException exception) {
            throw exception;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw DomainReconstitution.invalid("ToolCall", exception);
        }
    }

    private static void validatePersistedState(ToolCallPersistenceSnapshot snapshot, ToolCallStatus status) {
        Instant requestedAt = snapshot.requestedAt();
        Instant updatedAt = snapshot.completedAt() != null
                ? snapshot.completedAt()
                : snapshot.startedAt() != null ? snapshot.startedAt() : requestedAt;
        DomainReconstitution.requireChronological(requestedAt, updatedAt, "tool call");
        DomainReconstitution.requireWithinHistory(
                snapshot.startedAt(), requestedAt, updatedAt, "startedAt", "tool call");
        boolean emptyOutcome = snapshot.result() == null && snapshot.error() == null;
        boolean valid =
                switch (status) {
                    case REQUESTED ->
                        snapshot.startedAt() == null
                                && snapshot.completedAt() == null
                                && emptyOutcome
                                && snapshot.version() == 0;
                    case VALIDATING ->
                        snapshot.startedAt() == null
                                && snapshot.completedAt() == null
                                && emptyOutcome
                                && snapshot.version() == 1;
                    case POLICY_CHECK ->
                        snapshot.startedAt() == null
                                && snapshot.completedAt() == null
                                && emptyOutcome
                                && snapshot.version() == 2;
                    case WAITING_APPROVAL ->
                        snapshot.startedAt() == null
                                && snapshot.completedAt() == null
                                && emptyOutcome
                                && snapshot.version() == 3;
                    case APPROVED ->
                        snapshot.startedAt() == null
                                && snapshot.completedAt() == null
                                && emptyOutcome
                                && snapshot.version() == 4;
                    case RUNNING ->
                        snapshot.startedAt() != null
                                && snapshot.completedAt() == null
                                && emptyOutcome
                                && snapshot.version() >= 3;
                    case COMPLETED ->
                        snapshot.startedAt() != null
                                && snapshot.completedAt() != null
                                && snapshot.result() != null
                                && snapshot.result().successful()
                                && snapshot.error() == null
                                && snapshot.version() >= 4;
                    case FAILED ->
                        snapshot.startedAt() != null
                                && snapshot.completedAt() != null
                                && snapshot.result() == null
                                && snapshot.error() != null
                                && snapshot.version() >= 4;
                    case DENIED ->
                        snapshot.startedAt() == null
                                && snapshot.completedAt() != null
                                && emptyOutcome
                                && snapshot.version() >= 3;
                    case CANCELLED, TIMEOUT ->
                        snapshot.completedAt() != null && emptyOutcome && snapshot.version() >= 1;
                };
        if (!valid) {
            DomainReconstitution.invalid("tool call state is inconsistent for status " + status);
        }
    }

    public ToolCallPersistenceSnapshot persistenceSnapshot() {
        return new ToolCallPersistenceSnapshot(
                DomainReconstitution.SCHEMA_VERSION,
                id,
                runId,
                stepId,
                providerCorrelationId,
                idempotencyKey,
                toolName,
                toolVersion,
                arguments,
                requestedAt,
                status.name(),
                startedAt,
                completedAt,
                result,
                error,
                version);
    }

    public void beginValidation() {
        transition(ToolCallStatus.REQUESTED, ToolCallStatus.VALIDATING);
    }

    public void beginPolicyCheck() {
        transition(ToolCallStatus.VALIDATING, ToolCallStatus.POLICY_CHECK);
    }

    public void waitForApproval() {
        transition(ToolCallStatus.POLICY_CHECK, ToolCallStatus.WAITING_APPROVAL);
    }

    public void approve() {
        transition(ToolCallStatus.WAITING_APPROVAL, ToolCallStatus.APPROVED);
    }

    public void start(Instant at) {
        if (status != ToolCallStatus.POLICY_CHECK && status != ToolCallStatus.APPROVED) {
            throw invalidTransition(ToolCallStatus.RUNNING);
        }
        startedAt = requireChronological(at);
        status = ToolCallStatus.RUNNING;
        version++;
    }

    public void complete(ToolResult result, Instant at) {
        ToolResult completedResult = Objects.requireNonNull(result, "result must not be null");
        if (!completedResult.successful()) {
            throw new IllegalArgumentException("completed tool result must be successful");
        }
        transitionFromRunning(ToolCallStatus.COMPLETED, at);
        this.result = completedResult;
    }

    public void fail(ToolExecutionError error, Instant at) {
        ToolExecutionError executionError = Objects.requireNonNull(error, "error must not be null");
        transitionFromRunning(ToolCallStatus.FAILED, at);
        this.error = executionError;
    }

    public void deny(Instant at) {
        if (status != ToolCallStatus.POLICY_CHECK && status != ToolCallStatus.WAITING_APPROVAL) {
            throw invalidTransition(ToolCallStatus.DENIED);
        }
        finish(ToolCallStatus.DENIED, at);
    }

    public void cancel(Instant at) {
        requireNonTerminal();
        finish(ToolCallStatus.CANCELLED, at);
    }

    public void timeout(Instant at) {
        requireNonTerminal();
        finish(ToolCallStatus.TIMEOUT, at);
    }

    private void transitionFromRunning(ToolCallStatus target, Instant at) {
        if (status != ToolCallStatus.RUNNING) {
            throw invalidTransition(target);
        }
        finish(target, at);
    }

    private void finish(ToolCallStatus target, Instant at) {
        completedAt = requireChronological(at);
        status = target;
        version++;
    }

    private void transition(ToolCallStatus source, ToolCallStatus target) {
        if (status != source) {
            throw invalidTransition(target);
        }
        status = target;
        version++;
    }

    private Instant requireChronological(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        Instant floor = startedAt == null ? requestedAt : startedAt;
        if (at.isBefore(floor)) {
            throw new IllegalArgumentException("tool call time must not move backwards");
        }
        return at;
    }

    private void requireNonTerminal() {
        if (status == ToolCallStatus.COMPLETED
                || status == ToolCallStatus.FAILED
                || status == ToolCallStatus.DENIED
                || status == ToolCallStatus.CANCELLED
                || status == ToolCallStatus.TIMEOUT) {
            throw new IllegalStateException("terminal tool call cannot be changed");
        }
    }

    private IllegalStateException invalidTransition(ToolCallStatus target) {
        return new IllegalStateException("cannot transition tool call from " + status + " to " + target);
    }

    public ToolCallId id() {
        return id;
    }

    public AgentRunId runId() {
        return runId;
    }

    public AgentStepId stepId() {
        return stepId;
    }

    public ProviderToolCallCorrelationId providerCorrelationId() {
        return providerCorrelationId;
    }

    public RuntimeIdempotencyKey idempotencyKey() {
        return idempotencyKey;
    }

    public String toolName() {
        return toolName;
    }

    public String toolVersion() {
        return toolVersion;
    }

    public ToolArguments arguments() {
        return arguments;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public ToolCallStatus status() {
        return status;
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    public Optional<ToolResult> result() {
        return Optional.ofNullable(result);
    }

    public Optional<ToolExecutionError> error() {
        return Optional.ofNullable(error);
    }

    public long version() {
        return version;
    }
}
