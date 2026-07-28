package io.haifa.agent.sdk.memory;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryCandidate;
import io.haifa.agent.memory.api.MemoryCandidateDraft;
import io.haifa.agent.memory.api.MemoryCandidatePage;
import io.haifa.agent.memory.api.MemoryCandidateQuery;
import io.haifa.agent.memory.api.MemoryOperationException;
import io.haifa.agent.memory.api.MemoryPage;
import io.haifa.agent.memory.api.MemoryRecordQuery;
import io.haifa.agent.memory.api.MemoryRetentionPolicy;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryService;
import io.haifa.agent.memory.api.MemoryVisibility;
import io.haifa.agent.sdk.api.AgentRuns;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.conversation.ConversationService;
import io.haifa.agent.sdk.internal.CanonicalSdkDigest;
import io.haifa.agent.sdk.product.ProductMemoryPolicy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Product-facing Memory use cases with trusted caller, review, pagination, and safe error boundaries. */
public final class AgentMemories {
    private final MemoryService delegate;
    private final ProductMemoryPolicy productPolicy;
    private final SdkCallerProvider callers;
    private final ConversationService conversations;
    private final AgentRuns runs;
    private final AtomicBoolean closed;

    public AgentMemories(
            MemoryService delegate,
            ProductMemoryPolicy productPolicy,
            SdkCallerProvider callers,
            ConversationService conversations,
            AgentRuns runs,
            AtomicBoolean closed) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.productPolicy = Objects.requireNonNull(productPolicy, "productPolicy must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.closed = Objects.requireNonNull(closed, "closed must not be null");
    }

    public MemoryCandidate propose(ProposeMemoryCommand command) {
        return execute("memory.propose", command == null ? "null" : command.idempotencyKey(), () -> {
            ProposeMemoryCommand safe = Objects.requireNonNull(command, "command must not be null");
            validateContent(safe);
            SdkCaller caller = caller();
            return delegate.propose(draft(safe, scope(safe.scope(), caller)), actor(caller));
        });
    }

    public MemoryCandidate revise(ReviseMemoryCandidateCommand command) {
        return execute("memory.revise", command == null ? "null" : command.idempotencyKey(), () -> {
            ReviseMemoryCandidateCommand safe = Objects.requireNonNull(command, "command must not be null");
            validateContent(safe.revision());
            SdkCaller caller = caller();
            return delegate.revise(
                    safe.candidateId(),
                    draft(safe.revision(), scope(safe.revision().scope(), caller)),
                    safe.expectedRevision(),
                    actor(caller),
                    safe.idempotencyKey());
        });
    }

    public Memory approve(ReviewMemoryCandidateCommand command) {
        return execute("memory.approve", command == null ? "null" : command.idempotencyKey(), () -> {
            ReviewMemoryCandidateCommand safe = Objects.requireNonNull(command, "command must not be null");
            return delegate.approve(
                    safe.candidateId(), safe.expectedRevision(), actor(caller()), safe.idempotencyKey());
        });
    }

    public MemoryCandidate reject(RejectMemoryCandidateCommand command) {
        return execute("memory.reject", command == null ? "null" : command.idempotencyKey(), () -> {
            RejectMemoryCandidateCommand safe = Objects.requireNonNull(command, "command must not be null");
            return delegate.reject(
                    safe.candidateId(), safe.expectedRevision(), actor(caller()), safe.reason(), safe.idempotencyKey());
        });
    }

    public Memory invalidate(InvalidateMemoryCommand command) {
        return execute("memory.invalidate", command == null ? "null" : command.idempotencyKey(), () -> {
            InvalidateMemoryCommand safe = Objects.requireNonNull(command, "command must not be null");
            return delegate.invalidate(safe.memory(), actor(caller()), safe.reason(), safe.idempotencyKey());
        });
    }

    public MemoryCandidatePage candidates(MemoryCandidateListQuery query) {
        return execute("memory.candidates", "query", () -> {
            MemoryCandidateListQuery safe = Objects.requireNonNull(query, "query must not be null");
            validateLimit(safe.limit());
            SdkCaller caller = caller();
            return delegate.queryCandidates(
                    new MemoryCandidateQuery(
                            scope(safe.scope(), caller),
                            safe.statuses(),
                            safe.kinds(),
                            safe.updatedBefore(),
                            safe.after(),
                            safe.limit()),
                    actor(caller));
        });
    }

    public MemoryPage memories(MemoryListQuery query) {
        return execute("memory.memories", "query", () -> {
            MemoryListQuery safe = Objects.requireNonNull(query, "query must not be null");
            validateLimit(safe.limit());
            SdkCaller caller = caller();
            return delegate.queryMemories(
                    new MemoryRecordQuery(
                            scope(safe.scope(), caller),
                            safe.statuses(),
                            safe.kinds(),
                            safe.updatedBefore(),
                            safe.after(),
                            safe.limit()),
                    actor(caller));
        });
    }

    private MemoryScope scope(MemoryScopeSpec spec, SdkCaller caller) {
        String target =
                switch (spec.type()) {
                    case USER -> caller.principal().principalId();
                    case SESSION -> requireConversation(spec.targetId().orElseThrow());
                    case RUN -> requireRun(spec.targetId().orElseThrow());
                };
        return new MemoryScope(
                caller.tenant(),
                caller.principal(),
                spec.type(),
                target,
                MemoryVisibility.OWNER_ONLY,
                spec.securityLabels());
    }

    private String requireConversation(String sessionId) {
        AgentSessionId id = new AgentSessionId(sessionId);
        if (conversations.find(id).isEmpty()) throw new MemoryOperationException("MEMORY_SCOPE_UNAVAILABLE");
        return id.value();
    }

    private String requireRun(String runId) {
        AgentRunId id = new AgentRunId(runId);
        var view = runs.view(id).orElseThrow(() -> new MemoryOperationException("MEMORY_SCOPE_UNAVAILABLE"));
        if (conversations.find(view.sessionId()).isEmpty()) {
            throw new MemoryOperationException("MEMORY_SCOPE_UNAVAILABLE");
        }
        return id.value();
    }

    private static MemoryCandidateDraft draft(ProposeMemoryCommand command, MemoryScope scope) {
        return new MemoryCandidateDraft(
                command.idempotencyKey(),
                scope,
                command.kind(),
                command.subjectKey(),
                command.content(),
                command.sources(),
                command.evidence(),
                MemoryRetentionPolicy.RETAIN,
                false,
                command.replacesMemoryRef());
    }

    private void validateContent(ProposeMemoryCommand command) {
        if (command.content().boundedText().length() > productPolicy.maxCandidateContentChars()) {
            throw new IllegalArgumentException("Memory candidate content exceeds the Product Profile limit");
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
        return new MemoryActor(caller.tenant(), caller.principal(), caller.permissions());
    }

    private <T> T execute(String operation, String correlationInput, Supplier<T> work) {
        String correlation = CanonicalSdkDigest.sha256("sdk-memory-error-v1", operation, correlationInput)
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
