package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTargets;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.util.Objects;

/** Re-reads the Runtime facts behind a source Tool Call; the correlation id itself grants nothing. */
public final class RuntimeToolExecutionVerifier {
    private final RunStateRepository runs;
    private final RuntimeStateRepository state;
    private final InteractionPort interactions;
    private final ToolRequestCanonicalizer canonicalizer;
    private final PublicToolPolicy policy;
    private final FrozenToolBindingResolver bindings = new FrozenToolBindingResolver();

    public RuntimeToolExecutionVerifier(
            RunStateRepository runs,
            RuntimeStateRepository state,
            InteractionPort interactions,
            ToolRequestCanonicalizer canonicalizer,
            PublicToolPolicy policy) {
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.interactions = Objects.requireNonNull(interactions, "interactions must not be null");
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public void verify(
            TenantRef tenant,
            String runRef,
            PrincipalRef principal,
            ToolCallId sourceToolCallId,
            IntentCheck intentCheck) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(sourceToolCallId, "sourceToolCallId must not be null");
        Objects.requireNonNull(intentCheck, "intentCheck must not be null");

        AgentRunId runId;
        try {
            runId = new AgentRunId(runRef);
        } catch (RuntimeException exception) {
            throw denied("runtime execution run identity is invalid");
        }
        var run = runs.find(runId).orElseThrow(() -> denied("runtime execution run is unavailable"));
        if (!run.tenant().equals(tenant) || !run.principal().equals(principal)) {
            throw denied("runtime execution subject does not own the source run");
        }
        var sources = state.toolCalls(runId).stream()
                .filter(candidate -> candidate.id().equals(sourceToolCallId))
                .toList();
        if (sources.size() != 1) {
            throw denied("runtime execution source is not uniquely persisted");
        }
        var source = sources.getFirst();
        if (!source.runId().equals(runId) || source.status() != ToolCallStatus.RUNNING) {
            throw denied("runtime execution source is stale or terminal");
        }
        RuntimeConfigurationSnapshot configuration = state.configuration(run.configurationSnapshot())
                .orElseThrow(() -> denied("runtime execution configuration is unavailable"));
        ToolRequest persisted = new ToolRequest(
                source.id(),
                source.providerCorrelationId(),
                source.idempotencyKey(),
                source.toolName(),
                source.toolVersion(),
                source.arguments());
        FrozenToolBinding binding;
        ToolRequest canonical;
        try {
            binding = bindings.resolve(configuration.toolBindings(), persisted);
            canonical = Objects.requireNonNull(
                    canonicalizer.canonicalize(run, binding, persisted), "tool request canonicalizer returned null");
        } catch (RuntimeException exception) {
            throw denied("runtime execution frozen Tool facts are invalid");
        }
        if (!canonical.equals(persisted)) {
            throw denied("runtime execution canonical arguments drifted");
        }
        intentCheck.verify(configuration, binding, canonical);

        var decision = policy.evaluate(run, binding, canonical);
        if (decision.effect() == PolicyEffect.DENY) {
            throw denied("runtime execution is currently denied by product policy");
        }
        if (decision.effect() == PolicyEffect.ALLOW) return;

        var expected = ToolApprovalTargets.ordinary(run, source.id(), binding, canonical, decision);
        long candidates = interactions.toolApprovalRecords(runId, source.id()).stream()
                .filter(record -> record.state() == InteractionState.APPLIED)
                .filter(record -> isOrdinaryApprovalType(record.request().type()))
                .filter(record -> record.action()
                        .filter(InteractionAction.APPROVE::equals)
                        .isPresent())
                .filter(record -> record.request().approval())
                .filter(record -> record.request().tenant().equals(run.tenant()))
                .filter(record -> record.request().requester().equals(run.principal()))
                .filter(record -> record.request().target().equals(expected))
                .count();
        if (candidates != 1) {
            throw denied(
                    candidates == 0
                            ? "runtime execution lacks a current exact approval"
                            : "runtime execution approval state is ambiguous");
        }
    }

    private static boolean isOrdinaryApprovalType(String type) {
        return "tool-approval".equals(type) || "tool-reauthentication".equals(type);
    }

    @FunctionalInterface
    public interface IntentCheck {
        void verify(RuntimeConfigurationSnapshot configuration, FrozenToolBinding binding, ToolRequest request);
    }

    private static SecurityException denied(String message) {
        return new SecurityException(message);
    }
}
