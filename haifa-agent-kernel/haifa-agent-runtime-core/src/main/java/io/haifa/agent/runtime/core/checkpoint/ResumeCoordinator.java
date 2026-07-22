package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.api.ResumeAgentRunRequest;
import io.haifa.agent.runtime.core.bootstrap.RunAccessValidator;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.storage.CheckpointRepository;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.tool.api.ToolInvoker;
import java.util.Objects;
import java.util.Optional;

/** Validates and applies the durable state needed before a resumed attempt is scheduled. */
public final class ResumeCoordinator {
    private final InteractionPort interactions;
    private final CheckpointRepository checkpoints;
    private final ResumeCheckpointSelector selections;
    private final RunTransitionCoordinator transitions;
    private final RuntimeStateRepository state;
    private final RunAccessValidator access;
    private final CheckpointManager checkpointManager;
    private final ToolInvoker tools;

    public ResumeCoordinator(
            InteractionPort interactions,
            CheckpointRepository checkpoints,
            ResumeCheckpointSelector selections,
            RunTransitionCoordinator transitions,
            RuntimeStateRepository state,
            RunAccessValidator access,
            CheckpointManager checkpointManager,
            ToolInvoker tools) {
        this.interactions = Objects.requireNonNull(interactions);
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.selections = Objects.requireNonNull(selections);
        this.transitions = Objects.requireNonNull(transitions);
        this.state = Objects.requireNonNull(state);
        this.access = Objects.requireNonNull(access);
        this.checkpointManager = Objects.requireNonNull(checkpointManager);
        this.tools = Objects.requireNonNull(tools);
    }

    public Optional<CheckpointId> prepare(AgentRun run, ResumeAgentRunRequest request, RuntimeCallerContext caller) {
        validate(run, request, caller);
        Optional<CheckpointId> checkpoint = request.checkpointId().or(() -> latestFor(run));
        checkpoint.ifPresent(checkpointId -> selections.select(run.id(), checkpointId));
        transitions.resumed(run);
        return checkpoint;
    }

    public void validate(AgentRun run, ResumeAgentRunRequest request, RuntimeCallerContext caller) {
        access.validate(caller, run.sessionId(), run.project());
        var configuration = state.configuration(run.configurationSnapshot())
                .orElseThrow(() -> new IllegalStateException("run configuration snapshot is unavailable or corrupt"));
        if (!configuration.definitionId().equals(run.agentDefinitionId())
                || !configuration.definitionVersion().equals(run.agentDefinitionVersion())
                || !configuration.profileId().equals(run.productProfileId())
                || !configuration.profileVersion().equals(run.productProfileVersion())
                || configuration.runType() != run.runType()
                || !configuration.budget().equals(run.budget())
                || !configuration.limits().equals(run.limits())) {
            throw new IllegalStateException("run configuration snapshot does not match the frozen run configuration");
        }
        configuration.toolBindings().forEach(tools::validateBinding);
        if (interactions.pending(run.id()).isPresent()) {
            throw new IllegalStateException("pending interaction must be resolved through InteractionResponse");
        }
        request.checkpointId().ifPresent(checkpointId -> {
            boolean belongsToRun = checkpoints.checkpointsFor(run.id()).stream()
                    .anyMatch(checkpoint -> checkpoint.id().equals(checkpointId));
            if (!belongsToRun || checkpoints.state(checkpointId.value()).isEmpty()) {
                throw new IllegalArgumentException("selected checkpoint is not a valid checkpoint of the run");
            }
            checkpointManager.validateState(
                    run, checkpoints.state(checkpointId.value()).orElseThrow());
        });
    }

    public Optional<CheckpointId> latestFor(AgentRun run) {
        return checkpoints.latest(run.id()).map(checkpoint -> checkpoint.id());
    }
}
