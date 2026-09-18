package io.haifa.agent.runtime.core.model.continuation;

import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ModelContinuationRepository {
    AgentMessage appendSessionMessageWithContinuation(SessionMessageDraft message, ModelContinuationDraft draft);

    Optional<ModelContinuationRecord> continuationForMessage(AgentMessageId messageId);

    List<ModelContinuationRecord> modelContinuations(AgentRunId runId);

    SensitiveModelReasoning resolveContinuation(
            AgentMessageId messageId, ResolvedModelSnapshot model, Set<String> toolCorrelationIds);
}
