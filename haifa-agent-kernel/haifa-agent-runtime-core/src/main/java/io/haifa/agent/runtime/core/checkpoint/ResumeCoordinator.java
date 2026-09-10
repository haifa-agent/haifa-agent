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
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import io.haifa.agent.tool.api.ToolInvoker;
import java.util.Objects;
import java.util.Optional;

/** Validates and applies the durable state needed before a resumed attempt is scheduled. */
public final class ResumeCoordinator {
    private final InteractionPort interactions;
    private final CheckpointRepository checkpoints;
    private final RunTransitionCoordinator transitions;
    private final RuntimeStateRepository state;
    private final RunAccessValidator access;
    private final ToolInvoker tools;
    private final SkillContentLoader skills;

    public ResumeCoordinator(
            InteractionPort interactions,
            CheckpointRepository checkpoints,
            RunTransitionCoordinator transitions,
            RuntimeStateRepository state,
            RunAccessValidator access,
            ToolInvoker tools) {
        this(interactions, checkpoints, transitions, state, access, tools, SkillContentLoader.empty());
    }

    public ResumeCoordinator(
            InteractionPort interactions,
            CheckpointRepository checkpoints,
            RunTransitionCoordinator transitions,
            RuntimeStateRepository state,
            RunAccessValidator access,
            ToolInvoker tools,
            SkillContentLoader skills) {
        this.interactions = Objects.requireNonNull(interactions);
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.transitions = Objects.requireNonNull(transitions);
        this.state = Objects.requireNonNull(state);
        this.access = Objects.requireNonNull(access);
        this.tools = Objects.requireNonNull(tools);
        this.skills = Objects.requireNonNull(skills);
    }

    /** Applies a resume only after validate has succeeded in the same resume Unit of Work. */
    public Optional<CheckpointId> prepareValidated(AgentRun run, ResumeAgentRunRequest request) {
        Optional<CheckpointId> checkpoint = Optional.of(checkpoints
                .latest(run.id())
                .orElseThrow(() -> new IllegalStateException("intentional pause boundary is unavailable"))
                .id());
        transitions.resumed(run);
        return checkpoint;
    }

    public void validate(AgentRun run, ResumeAgentRunRequest request, RuntimeCallerContext caller) {
        access.validate(caller, run.sessionId(), run.project());
        var configuration = state.configuration(run.configurationSnapshot())
                .orElseThrow(() -> new IllegalStateException("run configuration snapshot is unavailable or corrupt"));
        java.util.List<String> mismatches = new java.util.ArrayList<>();
        if (!configuration.definitionId().equals(run.agentDefinitionId())) mismatches.add("definitionId");
        if (!configuration.definitionVersion().equals(run.agentDefinitionVersion()))
            mismatches.add("definitionVersion");
        if (!configuration.profileId().equals(run.productProfileId())) mismatches.add("profileId");
        if (!configuration.profileVersion().equals(run.productProfileVersion())) mismatches.add("profileVersion");
        if (!configuration.runType().equals(run.runType())) mismatches.add("runType");
        if (!configuration.budget().equals(run.budget())) mismatches.add("budget");
        if (!configuration.limits().equals(run.limits())) mismatches.add("limits");
        if (!mismatches.isEmpty()) {
            throw new IllegalStateException(
                    "run configuration snapshot does not match frozen fields: " + String.join(",", mismatches));
        }
        configuration.toolBindings().forEach(tools::validateBinding);
        var visibility = new SkillVisibilityContext(
                run.tenant(),
                run.principal(),
                run.project(),
                run.project().isPresent(),
                java.util.EnumSet.allOf(SkillScope.class));
        configuration.skillBindings().forEach(binding -> skills.validateBinding(binding, visibility));
        if (interactions.pending(run.id()).isPresent()) {
            throw new IllegalStateException("pending interaction must be resolved through InteractionResponse");
        }
    }
}
