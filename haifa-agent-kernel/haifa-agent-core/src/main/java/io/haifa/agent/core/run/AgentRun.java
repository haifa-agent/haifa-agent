package io.haifa.agent.core.run;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.reference.InteractionRequestRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Aggregate root for one reproducible, traceable logical Agent execution. */
public final class AgentRun {

    private static final Set<AgentRunOutcome> COMPLETED_OUTCOMES = EnumSet.of(
            AgentRunOutcome.SUCCESS,
            AgentRunOutcome.PARTIAL_SUCCESS,
            AgentRunOutcome.NO_ACTION_REQUIRED,
            AgentRunOutcome.INSUFFICIENT_INFORMATION);

    private final AgentRunId id;
    private final AgentRunId rootRunId;
    private final AgentRunId parentRunId;
    private final AgentSessionId sessionId;
    private final ProjectRef project;
    private final TenantRef tenant;
    private final PrincipalRef principal;
    private final AgentDefinitionId agentDefinitionId;
    private final AgentDefinitionVersion agentDefinitionVersion;
    private final String productProfileId;
    private final String productProfileVersion;
    private final AgentRunType runType;
    private final AgentInvocationMode invocationMode;
    private final int depth;
    private final String objective;
    private final AgentRunBudget budget;
    private final AgentRunLimits limits;
    private final RunConfigurationSnapshotRef configurationSnapshot;
    private final Instant createdAt;

    private AgentRunStatus status = AgentRunStatus.PENDING;
    private AgentRunUsage usage = AgentRunUsage.ZERO;
    private AgentRunResult result;
    private AgentError error;
    private InteractionRequestRef waitingFor;
    private RunTerminationReason terminationReason;
    private Instant queuedAt;
    private Instant startedAt;
    private Instant suspendedAt;
    private Instant resumedAt;
    private Instant completedAt;
    private Instant updatedAt;
    private long version;

    private AgentRun(
            AgentRunId id,
            AgentRunId rootRunId,
            AgentRunId parentRunId,
            AgentInvocationMode invocationMode,
            int depth,
            AgentRunSpec spec,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.rootRunId = Objects.requireNonNull(rootRunId, "rootRunId must not be null");
        this.parentRunId = parentRunId;
        this.invocationMode = Objects.requireNonNull(invocationMode, "invocationMode must not be null");
        this.depth = depth;
        Objects.requireNonNull(spec, "spec must not be null");
        this.sessionId = spec.sessionId();
        this.project = spec.project();
        this.tenant = spec.tenant();
        this.principal = spec.principal();
        this.agentDefinitionId = spec.agentDefinitionId();
        this.agentDefinitionVersion = spec.agentDefinitionVersion();
        this.productProfileId = spec.productProfileId();
        this.productProfileVersion = spec.productProfileVersion();
        this.runType = spec.runType();
        this.objective = spec.objective();
        this.budget = spec.budget();
        this.limits = spec.limits();
        this.configurationSnapshot = spec.configurationSnapshot();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = createdAt;
        validateHierarchy();
    }

    public static AgentRun createRoot(AgentRunId id, AgentRunSpec spec, Instant createdAt) {
        return new AgentRun(id, id, null, AgentInvocationMode.ROOT, 0, spec, createdAt);
    }

    public static AgentRun createChild(
            AgentRunId id, AgentRun parent, AgentInvocationMode invocationMode, AgentRunSpec spec, Instant createdAt) {
        Objects.requireNonNull(parent, "parent must not be null");
        if (parent.status.isTerminal()) {
            throw new IllegalStateException("a terminal parent cannot create a child run");
        }
        int childDepth = Math.addExact(parent.depth, 1);
        if (childDepth > parent.limits.maxDepth() || childDepth > spec.limits().maxDepth()) {
            throw new IllegalArgumentException("child run depth exceeds configured limit");
        }
        return new AgentRun(id, parent.rootRunId, parent.id, invocationMode, childDepth, spec, createdAt);
    }

    private void validateHierarchy() {
        if (depth < 0) {
            throw new IllegalArgumentException("run depth must not be negative");
        }
        if (invocationMode == AgentInvocationMode.ROOT) {
            if (!id.equals(rootRunId) || parentRunId != null || depth != 0) {
                throw new IllegalArgumentException("root run hierarchy is invalid");
            }
        } else if (parentRunId == null || id.equals(parentRunId) || id.equals(rootRunId) || depth < 1) {
            throw new IllegalArgumentException("child run hierarchy is invalid");
        }
    }

    public void markQueued(Instant at) {
        transitionFrom(AgentRunStatus.PENDING, AgentRunStatus.QUEUED, at);
        queuedAt = at;
    }

    public void start(Instant at) {
        if (status != AgentRunStatus.PENDING && status != AgentRunStatus.QUEUED) {
            throw invalidTransition(AgentRunStatus.RUNNING);
        }
        transition(AgentRunStatus.RUNNING, at);
        startedAt = at;
    }

    public void requestSuspend(Instant at) {
        transitionFrom(AgentRunStatus.RUNNING, AgentRunStatus.SUSPENDING, at);
    }

    public void suspend(Instant at) {
        transitionFrom(AgentRunStatus.SUSPENDING, AgentRunStatus.SUSPENDED, at);
        suspendedAt = at;
    }

    public void resume(Instant at) {
        if (status != AgentRunStatus.SUSPENDED
                && status != AgentRunStatus.WAITING_INTERACTION
                && status != AgentRunStatus.WAITING_APPROVAL) {
            throw invalidTransition(AgentRunStatus.RUNNING);
        }
        transition(AgentRunStatus.RUNNING, at);
        resumedAt = at;
        waitingFor = null;
    }

    public void waitForInteraction(InteractionRequestRef request, Instant at) {
        waitingFor(request, AgentRunStatus.WAITING_INTERACTION, at);
    }

    public void waitForApproval(InteractionRequestRef request, Instant at) {
        waitingFor(request, AgentRunStatus.WAITING_APPROVAL, at);
    }

    private void waitingFor(InteractionRequestRef request, AgentRunStatus target, Instant at) {
        Objects.requireNonNull(request, "request must not be null");
        transitionFrom(AgentRunStatus.RUNNING, target, at);
        waitingFor = request;
    }

    public void beginCompleting(Instant at) {
        transitionFrom(AgentRunStatus.RUNNING, AgentRunStatus.COMPLETING, at);
    }

    public void complete(AgentRunResult result, Instant at) {
        Objects.requireNonNull(result, "result must not be null");
        if (!COMPLETED_OUTCOMES.contains(result.outcome())) {
            throw new IllegalArgumentException("outcome " + result.outcome() + " is not valid for a completed run");
        }
        transitionFrom(AgentRunStatus.COMPLETING, AgentRunStatus.COMPLETED, at);
        this.result = result;
        this.completedAt = at;
    }

    public void fail(AgentError error, Instant at) {
        requireNonTerminal();
        this.error = Objects.requireNonNull(error, "error must not be null");
        transition(AgentRunStatus.FAILED, at);
        completedAt = at;
    }

    public void cancel(RunTerminationReason reason, Instant at) {
        requireNonTerminal();
        terminationReason = Objects.requireNonNull(reason, "reason must not be null");
        transition(AgentRunStatus.CANCELLED, at);
        completedAt = at;
    }

    public void timeout(RunTerminationReason reason, Instant at) {
        requireNonTerminal();
        terminationReason = Objects.requireNonNull(reason, "reason must not be null");
        transition(AgentRunStatus.TIMEOUT, at);
        completedAt = at;
    }

    public void recordUsage(AgentRunUsageDelta delta) {
        requireNonTerminal();
        usage = usage.plus(delta);
        version++;
    }

    private void transitionFrom(AgentRunStatus expected, AgentRunStatus target, Instant at) {
        if (status != expected) {
            throw invalidTransition(target);
        }
        transition(target, at);
    }

    private void transition(AgentRunStatus target, Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("run transition time must not move backwards");
        }
        status = target;
        updatedAt = at;
        version++;
    }

    private void requireNonTerminal() {
        if (status.isTerminal()) {
            throw new IllegalStateException("terminal run cannot be changed");
        }
    }

    private IllegalStateException invalidTransition(AgentRunStatus target) {
        return new IllegalStateException("cannot transition Agent run from " + status + " to " + target);
    }

    public AgentRunId id() {
        return id;
    }

    public AgentRunId rootRunId() {
        return rootRunId;
    }

    public Optional<AgentRunId> parentRunId() {
        return Optional.ofNullable(parentRunId);
    }

    public AgentSessionId sessionId() {
        return sessionId;
    }

    public Optional<ProjectRef> project() {
        return Optional.ofNullable(project);
    }

    public TenantRef tenant() {
        return tenant;
    }

    public PrincipalRef principal() {
        return principal;
    }

    public AgentDefinitionId agentDefinitionId() {
        return agentDefinitionId;
    }

    public AgentDefinitionVersion agentDefinitionVersion() {
        return agentDefinitionVersion;
    }

    public String productProfileId() {
        return productProfileId;
    }

    public String productProfileVersion() {
        return productProfileVersion;
    }

    public AgentRunType runType() {
        return runType;
    }

    public AgentInvocationMode invocationMode() {
        return invocationMode;
    }

    public int depth() {
        return depth;
    }

    public String objective() {
        return objective;
    }

    public AgentRunBudget budget() {
        return budget;
    }

    public AgentRunLimits limits() {
        return limits;
    }

    public RunConfigurationSnapshotRef configurationSnapshot() {
        return configurationSnapshot;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public AgentRunStatus status() {
        return status;
    }

    public AgentRunUsage usage() {
        return usage;
    }

    public Optional<AgentRunResult> result() {
        return Optional.ofNullable(result);
    }

    public Optional<AgentError> error() {
        return Optional.ofNullable(error);
    }

    public Optional<InteractionRequestRef> waitingFor() {
        return Optional.ofNullable(waitingFor);
    }

    public Optional<RunTerminationReason> terminationReason() {
        return Optional.ofNullable(terminationReason);
    }

    public Optional<Instant> queuedAt() {
        return Optional.ofNullable(queuedAt);
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> suspendedAt() {
        return Optional.ofNullable(suspendedAt);
    }

    public Optional<Instant> resumedAt() {
        return Optional.ofNullable(resumedAt);
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
