package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationRepository;
import io.haifa.agent.runtime.core.skill.SkillActivationRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface RuntimeStateRepository
        extends SessionMessageRepository,
                RuntimeMemorySelectionRepository,
                ModelContinuationRepository,
                SkillActivationRepository {

    void appendStep(AgentStep step);

    void appendToolCall(ToolCall toolCall);

    void savePlan(AgentPlan plan);

    List<AgentMessage> messages(AgentRunId runId);

    List<AgentStep> steps(AgentRunId runId);

    List<ToolCall> toolCalls(AgentRunId runId);

    /**
     * Loads only the authoritative Tool Calls referenced by an active context window.
     *
     * <p>The compatibility implementation still batches once per Run. Durable adapters should override this method
     * with a bounded set query so an old Run's complete Tool history does not re-enter the hot path.
     */
    default List<ToolCall> toolCallsByIds(Map<AgentRunId, Set<ToolCallId>> idsByRun) {
        return idsByRun.entrySet().stream()
                .flatMap(entry -> toolCalls(entry.getKey()).stream()
                        .filter(call -> entry.getValue().contains(call.id())))
                .toList();
    }

    /** Loads globally unique Tool Call ids when the originating Run is no longer in the active message window. */
    default List<ToolCall> toolCallsByIds(Set<ToolCallId> ids) {
        throw new UnsupportedOperationException("global Tool Call lookup is not implemented");
    }

    Optional<AgentPlan> plan(AgentRunId runId);

    void saveOutput(AgentRunId runId, String output);

    AgentMessage saveFinalOutputAndMessage(AgentRunId runId, String output, SessionMessageDraft message);

    Optional<String> output(AgentRunId runId);

    void saveConfiguration(RuntimeConfigurationSnapshot configuration);

    Optional<RuntimeConfigurationSnapshot> configuration(RunConfigurationSnapshotRef reference);
}
