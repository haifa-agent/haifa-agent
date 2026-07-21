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
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryRetriever;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryScopeType;
import io.haifa.agent.memory.api.MemorySecurityLabel;
import io.haifa.agent.memory.api.MemoryVisibility;
import io.haifa.agent.runtime.core.checkpoint.MemoryCheckpointRef;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.storage.RuntimeMemorySelection;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public List<ContextItem> select(AgentRun run, FrozenModelBinding model) {
        List<MemoryScope> scopes = List.of(
                scope(run, MemoryScopeType.RUN, run.id().value()),
                scope(run, MemoryScopeType.SESSION, run.sessionId().value()),
                scope(run, MemoryScopeType.USER, run.principal().principalId()));
        int budget = Math.max(128, Math.min(8_192, model.configuration().model().contextWindow() / 8));
        var retrieval = memories.retrieve(new MemoryQuery(
                run.tenant(),
                run.principal(),
                scopes,
                latestUserText(run),
                java.util.EnumSet.allOf(MemoryKind.class),
                Set.of(MemorySecurityLabel.INTERNAL, MemorySecurityLabel.CONFIDENTIAL),
                8,
                budget,
                time.now()));
        state.saveMemorySelection(
                run.id(),
                new RuntimeMemorySelection(
                        retrieval.results().stream()
                                .map(result -> new MemoryCheckpointRef(
                                        result.memory().id(),
                                        result.memory().version(),
                                        result.memory().scope()))
                                .toList(),
                        retrieval.policyVersion(),
                        retrieval.queryDigest()));
        return retrieval.results().stream()
                .map(result -> {
                    var memory = result.memory();
                    return new ContextItem(
                            new ContextItemId("memory-" + memory.id().value() + "-"
                                    + memory.version().value()),
                            ContextItemType.MEMORY_REFERENCE,
                            new MemoryReferenceContent(
                                    memory.id().value(),
                                    Long.toString(memory.version().value()),
                                    memory.content().orElseThrow().boundedText()),
                            result.estimatedTokens(),
                            ContextPriority.NORMAL,
                            ContextRetention.COMPRESSIBLE,
                            new ContextSecurity(
                                    memory.securityLabels().stream()
                                            .map(label -> label.name().toLowerCase(java.util.Locale.ROOT))
                                            .collect(java.util.stream.Collectors.toSet()),
                                    true),
                            new ContextProvenance(
                                    "governed-memory",
                                    memory.id().value(),
                                    Long.toString(memory.version().value()),
                                    memory.normalizedDigest()),
                            Map.of(
                                    "scope",
                                            memory.scope().type() + ":"
                                                    + memory.scope().targetId(),
                                    "selectionReason", result.selectionReason(),
                                    "sourceTypes",
                                            memory.sources().stream()
                                                    .map(source -> source.type().name())
                                                    .distinct()
                                                    .sorted()
                                                    .toList()
                                                    .toString()));
                })
                .toList();
    }

    private MemoryScope scope(AgentRun run, MemoryScopeType type, String target) {
        return new MemoryScope(run.tenant(), run.principal(), type, target, MemoryVisibility.OWNER_ONLY, Set.of());
    }

    private String latestUserText(AgentRun run) {
        return state.messagesAfter(run.sessionId(), MessageCursor.BEFORE_FIRST, Integer.MAX_VALUE).stream()
                .filter(message -> message.role() == MessageRole.USER
                        && message.status() == MessageStatus.COMPLETED
                        && message.visibility() != MessageVisibility.HIDDEN
                        && message.visibility() != MessageVisibility.REDACTED)
                .flatMap(message -> message.contents().stream())
                .filter(TextPart.class::isInstance)
                .map(TextPart.class::cast)
                .map(TextPart::text)
                .reduce((first, second) -> second)
                .orElse("");
    }
}
