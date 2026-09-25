package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunSpec;
import io.haifa.agent.runtime.api.AgentRunRequest;
import java.util.Objects;

/** Resolves and freezes every effective start-time dependency before persistence. */
public final class RunBootstrapper {
    private final DefinitionResolver definitions;
    private final ProfileResolver profiles;
    private final RunAccessValidator access;
    private final ConfigurationSnapshotFactory snapshots;
    private final CapabilityResolver capabilities;
    private final IdentifierGenerator ids;
    private final TimeProvider time;

    public RunBootstrapper(
            DefinitionResolver definitions,
            ProfileResolver profiles,
            RunAccessValidator access,
            ConfigurationSnapshotFactory snapshots,
            IdentifierGenerator ids,
            TimeProvider time) {
        this(definitions, profiles, access, snapshots, new DefaultCapabilityResolver(), ids, time);
    }

    public RunBootstrapper(
            DefinitionResolver definitions,
            ProfileResolver profiles,
            RunAccessValidator access,
            ConfigurationSnapshotFactory snapshots,
            CapabilityResolver capabilities,
            IdentifierGenerator ids,
            TimeProvider time) {
        this.definitions = Objects.requireNonNull(definitions);
        this.profiles = Objects.requireNonNull(profiles);
        this.access = Objects.requireNonNull(access);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.capabilities = Objects.requireNonNull(capabilities);
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
    }

    public BootstrapResult bootstrap(AgentRunRequest request, RuntimeCallerContext caller) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(caller, "caller must not be null");
        access.validate(caller, request.sessionId(), request.project());
        ResolvedProfile profile = profiles.resolve(request.productProfileId(), request.overrides());
        ResolvedDefinition definition =
                definitions.resolve(request.agentDefinitionId(), request.requestedDefinitionVersion());
        var effectiveCapabilities = capabilities.resolve(request, definition, profile);
        var configuration = snapshots.create(request, definition, profile, caller, effectiveCapabilities);
        AgentRunId runId = new AgentRunId(ids.nextValue());
        AgentRun run = AgentRun.createRoot(
                runId,
                new AgentRunSpec(
                        request.sessionId(),
                        request.project().orElse(null),
                        caller.tenant(),
                        caller.principal(),
                        definition.id(),
                        definition.version(),
                        profile.id(),
                        profile.version(),
                        profile.runType(),
                        request.objective(),
                        profile.budget(),
                        profile.limits(),
                        configuration.reference()),
                time.now());
        return new BootstrapResult(run, definition, profile, configuration);
    }

    /**
     * Freezes one child run of {@code parent} from an already narrowed definition and profile. The child is an
     * ordinary Run: it gets its own configuration snapshot, inherits the parent's tenant and principal, and the
     * Core aggregate enforces the hierarchy and depth invariants.
     */
    public BootstrapResult bootstrapChild(
            AgentRun parent,
            AgentRunId childRunId,
            AgentRunRequest request,
            ResolvedDefinition definition,
            ResolvedProfile profile) {
        Objects.requireNonNull(parent, "parent must not be null");
        Objects.requireNonNull(childRunId, "childRunId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        RuntimeCallerContext caller = new RuntimeCallerContext(parent.tenant(), parent.principal());
        var effectiveCapabilities = capabilities.resolve(request, definition, profile);
        var configuration = snapshots.create(request, definition, profile, caller, effectiveCapabilities);
        AgentRun child = AgentRun.createChild(
                childRunId,
                parent,
                io.haifa.agent.core.run.AgentInvocationMode.AGENT_AS_TOOL,
                new AgentRunSpec(
                        request.sessionId(),
                        request.project().orElse(null),
                        parent.tenant(),
                        parent.principal(),
                        definition.id(),
                        definition.version(),
                        profile.id(),
                        profile.version(),
                        profile.runType(),
                        request.objective(),
                        profile.budget(),
                        profile.limits(),
                        configuration.reference()),
                time.now());
        return new BootstrapResult(child, definition, profile, configuration);
    }
}
