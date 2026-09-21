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

    /**
     * Resolves an already loaded continuation without requiring another repository lookup.
     *
     * <p>The default preserves compatibility for repository implementations that have not yet specialized the
     * batched path. Durable adapters should override this method so validation and decryption consume the supplied
     * authoritative record directly.
     */
    default SensitiveModelReasoning resolveContinuation(
            ModelContinuationRecord record, ResolvedModelSnapshot model, Set<String> toolCorrelationIds) {
        return resolveContinuation(record.assistantMessageId(), model, toolCorrelationIds);
    }
}
