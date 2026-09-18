package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.item.ContextItemId;
import io.haifa.agent.context.item.ContextItemType;
import io.haifa.agent.context.item.ContextPriority;
import io.haifa.agent.context.item.ContextProvenance;
import io.haifa.agent.context.item.ContextRetention;
import io.haifa.agent.context.item.ContextSecurity;
import io.haifa.agent.context.item.MemoryReferenceContent;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.memory.api.MemoryContextRequest;
import io.haifa.agent.memory.api.MemoryRetriever;
import io.haifa.agent.runtime.core.checkpoint.MemoryCheckpointRef;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.storage.RuntimeMemorySelection;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.List;

/** Retrieves governed Memory using trusted Run identity and maps authorized results to Context IR. */
public final class MemoryContextSource {
    private final MemoryRetriever memories;
    private final RuntimeStateRepository state;
    private final TimeProvider time;

    public MemoryContextSource(MemoryRetriever memories, RuntimeStateRepository state, TimeProvider time) {
        this.memories = java.util.Objects.requireNonNull(memories);
        this.state = java.util.Objects.requireNonNull(state);
        this.time = java.util.Objects.requireNonNull(time);
    }

    public List<ContextItem> select(AgentRun run, FrozenModelBinding model, AgentLoopContext loopContext) {
        UserTurn turn = latestUserTurn(run);
        return loopContext
                .memorySelectionFor(turn.messageId())
                .orElseGet(() -> retrieveAndCache(run, model, loopContext, turn));
    }

    private List<ContextItem> retrieveAndCache(
            AgentRun run, FrozenModelBinding model, AgentLoopContext loopContext, UserTurn turn) {
        int budget = Math.max(128, Math.min(8_192, model.configuration().model().contextWindow() / 8));
        var retrieval = memories.contextFor(new MemoryContextRequest(
                run.tenant(),
                run.principal(),
                run.id().value(),
                run.sessionId().value(),
                turn.text(),
                budget,
                time.now()));
        state.saveMemorySelection(
                run.id(),
                new RuntimeMemorySelection(
                        retrieval.snippets().stream()
                                .map(result -> new MemoryCheckpointRef(result.id(), result.version(), result.scope()))
                                .toList(),
                        retrieval.policyVersion(),
                        retrieval.queryDigest()));
        List<ContextItem> items = retrieval.snippets().stream()
                .map(result -> {
                    return new ContextItem(
                            new ContextItemId("memory-" + result.id().value() + "-"
                                    + result.version().value()),
                            ContextItemType.MEMORY_REFERENCE,
                            new MemoryReferenceContent(
                                    result.id().value(),
                                    Long.toString(result.version().value()),
                                    result.text()),
                            result.estimatedTokens(),
                            ContextPriority.NORMAL,
                            ContextRetention.COMPRESSIBLE,
                            ContextSecurity.INTERNAL,
                            new ContextProvenance(
                                    "governed-memory",
                                    result.id().value(),
                                    Long.toString(result.version().value()),
                                    result.normalizedDigest()));
                })
                .toList();
        loopContext.cacheMemorySelection(turn.messageId(), items);
        return items;
    }

    private UserTurn latestUserTurn(AgentRun run) {
        return state.messagesAfter(run.sessionId(), MessageCursor.BEFORE_FIRST, Integer.MAX_VALUE).stream()
                .filter(message -> message.role() == MessageRole.USER
                        && message.status() == MessageStatus.COMPLETED
                        && message.visibility() != MessageVisibility.HIDDEN
                        && message.visibility() != MessageVisibility.REDACTED)
                .reduce((first, second) -> second)
                .map(message -> new UserTurn(
                        message.id(),
                        message.contents().stream()
                                .filter(TextPart.class::isInstance)
                                .map(TextPart.class::cast)
                                .map(TextPart::text)
                                .reduce((first, second) -> second)
                                .orElse("")))
                .orElseThrow(() ->
                        new IllegalStateException("a completed visible user message is required for Memory retrieval"));
    }

    private record UserTurn(io.haifa.agent.core.message.AgentMessageId messageId, String text) {}
}
