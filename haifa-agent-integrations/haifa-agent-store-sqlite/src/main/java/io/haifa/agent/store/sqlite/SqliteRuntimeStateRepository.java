package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationDraft;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationRecord;
import io.haifa.agent.runtime.core.skill.SkillActivationRepository;
import io.haifa.agent.runtime.core.storage.RecentMessageWindow;
import io.haifa.agent.runtime.core.storage.RuntimeMemorySelection;
import io.haifa.agent.runtime.core.storage.RuntimeMemorySelectionRepository;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.skill.api.SkillActivation;
import io.haifa.agent.skill.api.SkillAlias;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Complete SQLite implementation of the runtime loop state boundary. */
public final class SqliteRuntimeStateRepository implements RuntimeStateRepository {
    private final SqliteSessionMessageRepository messages;
    private final SqliteLoopStateComponent loop;
    private final RuntimeMemorySelectionRepository memory;
    private final SqliteModelContinuationRepository continuations;
    private final SkillActivationRepository skills;

    SqliteRuntimeStateRepository(
            SqliteSessionMessageRepository messages,
            SqliteLoopStateComponent loop,
            RuntimeMemorySelectionRepository memory,
            SqliteModelContinuationRepository continuations,
            SkillActivationRepository skills) {
        this.messages = Objects.requireNonNull(messages);
        this.loop = Objects.requireNonNull(loop);
        this.memory = Objects.requireNonNull(memory);
        this.continuations = Objects.requireNonNull(continuations);
        this.skills = Objects.requireNonNull(skills);
    }

    @Override
    public AgentMessage appendSessionMessage(SessionMessageDraft draft) {
        return messages.appendSessionMessage(draft);
    }

    @Override
    public List<AgentMessage> messagesAfter(AgentSessionId sessionId, MessageCursor cursor, int limit) {
        return messages.messagesAfter(sessionId, cursor, limit);
    }

    @Override
    public RecentMessageWindow recentMessages(AgentSessionId sessionId, MessageCursor cursor, int limit) {
        return messages.recentMessages(sessionId, cursor, limit);
    }

    @Override
    public Optional<MessageCursor> latestMessageCursor(AgentSessionId sessionId) {
        return messages.latestMessageCursor(sessionId);
    }

    @Override
    public Optional<AgentMessage> message(AgentMessageId id) {
        return messages.message(id);
    }

    @Override
    public AgentMessage redactMessage(AgentMessageId id) {
        return messages.redactMessage(id);
    }

    @Override
    public void appendStep(AgentStep step) {
        loop.appendStep(step);
    }

    @Override
    public void appendToolCall(ToolCall toolCall) {
        loop.appendToolCall(toolCall);
    }

    @Override
    public void savePlan(AgentPlan plan) {
        loop.savePlan(plan);
    }

    @Override
    public List<AgentMessage> messages(AgentRunId runId) {
        return messages.messagesForRun(runId);
    }

    @Override
    public List<AgentStep> steps(AgentRunId runId) {
        return loop.steps(runId);
    }

    @Override
    public List<ToolCall> toolCalls(AgentRunId runId) {
        return loop.toolCalls(runId);
    }

    @Override
    public Optional<AgentPlan> plan(AgentRunId runId) {
        return loop.plan(runId);
    }

    @Override
    public void saveOutput(AgentRunId runId, String output) {
        loop.saveOutput(runId, output);
    }

    @Override
    public AgentMessage saveFinalOutputAndMessage(AgentRunId runId, String output, SessionMessageDraft message) {
        return loop.saveFinalOutputAndMessage(runId, output, message);
    }

    @Override
    public Optional<String> output(AgentRunId runId) {
        return loop.output(runId);
    }

    @Override
    public void saveConfiguration(RuntimeConfigurationSnapshot configuration) {
        loop.saveConfiguration(configuration);
    }

    @Override
    public Optional<RuntimeConfigurationSnapshot> configuration(RunConfigurationSnapshotRef reference) {
        return loop.configuration(reference.snapshotId(), reference.contentHash());
    }

    @Override
    public void saveMemorySelection(AgentRunId runId, RuntimeMemorySelection selection) {
        memory.saveMemorySelection(runId, selection);
    }

    @Override
    public Optional<RuntimeMemorySelection> memorySelection(AgentRunId runId) {
        return memory.memorySelection(runId);
    }

    @Override
    public AgentMessage appendSessionMessageWithContinuation(
            SessionMessageDraft message, ModelContinuationDraft draft) {
        return continuations.appendSessionMessageWithContinuation(message, draft);
    }

    @Override
    public Optional<ModelContinuationRecord> continuationForMessage(AgentMessageId messageId) {
        return continuations.continuationForMessage(messageId);
    }

    @Override
    public List<ModelContinuationRecord> modelContinuations(AgentRunId runId) {
        return continuations.modelContinuations(runId);
    }

    @Override
    public SensitiveModelReasoning resolveContinuation(
            AgentMessageId messageId, ResolvedModelSnapshot model, Set<String> toolCorrelationIds) {
        return continuations.resolveContinuation(messageId, model, toolCorrelationIds);
    }

    @Override
    public SkillActivation saveSkillActivation(
            AgentRunId runId, SkillActivation activation, long instructionLimit, long tokenLimit) {
        return skills.saveSkillActivation(runId, activation, instructionLimit, tokenLimit);
    }

    @Override
    public Optional<SkillActivation> skillActivation(AgentRunId runId, SkillAlias alias) {
        return skills.skillActivation(runId, alias);
    }

    @Override
    public List<SkillActivation> skillActivations(AgentRunId runId) {
        return skills.skillActivations(runId);
    }

    @Override
    public long addSkillResourceReadBytes(AgentRunId runId, long bytes, long maximum) {
        return skills.addSkillResourceReadBytes(runId, bytes, maximum);
    }
}
