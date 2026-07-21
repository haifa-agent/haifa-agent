package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import java.util.List;
import java.util.Optional;

public interface RuntimeStateRepository extends SessionMessageRepository, RuntimeMemorySelectionRepository {

    void appendStep(AgentStep step);

    void appendToolCall(ToolCall toolCall);

    void savePlan(AgentPlan plan);

    List<AgentMessage> messages(AgentRunId runId);

    List<AgentStep> steps(AgentRunId runId);

    List<ToolCall> toolCalls(AgentRunId runId);

    Optional<AgentPlan> plan(AgentRunId runId);

    void saveOutput(AgentRunId runId, String output);

    AgentMessage saveFinalOutputAndMessage(AgentRunId runId, String output, SessionMessageDraft message);

    Optional<String> output(AgentRunId runId);

    void saveConfiguration(RuntimeConfigurationSnapshot configuration);

    Optional<RuntimeConfigurationSnapshot> configuration(RunConfigurationSnapshotRef reference);
}
