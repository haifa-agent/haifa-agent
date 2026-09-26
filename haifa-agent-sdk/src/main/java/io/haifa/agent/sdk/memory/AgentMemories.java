package io.haifa.agent.sdk.memory;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryDraft;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryOperationException;
import io.haifa.agent.memory.api.MemoryPage;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryService;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.conversation.ConversationService;
import io.haifa.agent.sdk.internal.CanonicalSdkDigest;
import io.haifa.agent.sdk.product.ProductMemoryPolicy;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Product-facing direct Memory CRUD. Tenant and owner always come from the trusted {@link SdkCaller}; callers only
 * choose the USER, AGENT or SESSION bucket through {@link MemoryScopeSpec}.
 */
public final class AgentMemories {
    private final MemoryService delegate;
    private final ProductMemoryPolicy productPolicy;
    private final SdkCallerProvider callers;
    private final ConversationService conversations;
    private final AtomicBoolean closed;

    public AgentMemories(
            MemoryService delegate,
            ProductMemoryPolicy productPolicy,
            SdkCallerProvider callers,
            ConversationService conversations,
            AtomicBoolean closed) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.productPolicy = Objects.requireNonNull(productPolicy, "productPolicy must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.closed = Objects.requireNonNull(closed, "closed must not be null");
    }

    /** Creates a memory, replaces the content of the same subject, or returns it unchanged for a duplicate. */
    public Memory put(PutMemoryCommand command) {
        return execute("memory.put", () -> {
            PutMemoryCommand safe = Objects.requireNonNull(command, "command must not be null");
            validateContent(safe.content());
            SdkCaller caller = caller();
            return delegate.put(
                    new MemoryDraft(
                            scope(safe.scope(), caller),
                            safe.kind(),
                            safe.subjectKey(),
                            safe.content(),
                            safe.source(),
                            safe.observedAt()),
                    actor(caller));
        });
    }

    public Memory update(MemoryId id, long expectedRevision, String content) {
        return execute("memory.update", () -> {
            validateContent(Objects.requireNonNull(content, "content must not be null"));
            return delegate.update(
                    Objects.requireNonNull(id, "id must not be null"), expectedRevision, content, actor(caller()));
        });
    }

    public void delete(MemoryId id, long expectedRevision) {
        execute("memory.delete", () -> {
            delegate.delete(Objects.requireNonNull(id, "id must not be null"), expectedRevision, actor(caller()));
            return null;
        });
    }

    public Optional<Memory> find(MemoryId id) {
        return execute(
                "memory.find", () -> delegate.find(Objects.requireNonNull(id, "id must not be null"), actor(caller())));
    }

    public MemoryPage list(MemoryListQuery query) {
        return execute("memory.list", () -> {
            MemoryListQuery safe = Objects.requireNonNull(query, "query must not be null");
            validateLimit(safe.limit());
            SdkCaller caller = caller();
            return delegate.list(
                    new MemoryQuery(scope(safe.scope(), caller), safe.kinds(), safe.text(), safe.after(), safe.limit()),
                    actor(caller));
        });
    }

    /** Deletes every memory in the bucket; writes observed before the clear are refused afterwards. */
    public int clear(MemoryScopeSpec scope) {
        return execute("memory.clear", () -> {
            SdkCaller caller = caller();
            return delegate.clear(
                    scope(Objects.requireNonNull(scope, "scope must not be null"), caller), actor(caller));
        });
    }

    private MemoryScope scope(MemoryScopeSpec spec, SdkCaller caller) {
        return switch (spec.type()) {
            case USER -> MemoryScope.user(caller.tenant(), caller.principal());
            case AGENT ->
                MemoryScope.agent(
                        caller.tenant(),
                        caller.principal(),
                        new AgentDefinitionId(spec.targetId().orElseThrow()).value());
            case SESSION ->
                MemoryScope.session(
                        caller.tenant(),
                        caller.principal(),
                        requireConversation(spec.targetId().orElseThrow()));
        };
    }

    private String requireConversation(String sessionId) {
        AgentSessionId id = new AgentSessionId(sessionId);
        if (conversations.find(id).isEmpty()) throw new MemoryOperationException("MEMORY_SCOPE_UNAVAILABLE");
        return id.value();
    }

    private void validateContent(String content) {
        if (content.trim().length() > productPolicy.maxContentChars()) {
            throw new IllegalArgumentException("Memory content exceeds the Product Profile limit");
        }
    }

    private void validateLimit(int limit) {
        if (limit > productPolicy.maxQueryLimit()) {
            throw new IllegalArgumentException("Memory query limit exceeds the Product Profile limit");
        }
    }

    private SdkCaller caller() {
        return Objects.requireNonNull(callers.current(), "caller provider returned null");
    }

    private static MemoryActor actor(SdkCaller caller) {
        return new MemoryActor(caller.tenant(), caller.principal());
    }

    private <T> T execute(String operation, Supplier<T> work) {
        String correlation = CanonicalSdkDigest.sha256("sdk-memory-error-v1", operation, "request")
                .substring(7, 23);
        if (closed.get()) throw new MemoryException("AGENT_CLOSED", operation, correlation);
        try {
            return work.get();
        } catch (MemoryException exception) {
            throw exception;
        } catch (MemoryOperationException exception) {
            throw new MemoryException(exception.code(), operation, correlation);
        } catch (IllegalArgumentException exception) {
            throw new MemoryException("MEMORY_INVALID_REQUEST", operation, correlation);
        } catch (RuntimeException exception) {
            throw new MemoryException("MEMORY_OPERATION_FAILED", operation, correlation);
        }
    }
}
