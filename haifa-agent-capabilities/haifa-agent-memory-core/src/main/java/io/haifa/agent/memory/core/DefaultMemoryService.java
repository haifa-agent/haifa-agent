package io.haifa.agent.memory.core;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimePrecision;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryDraft;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryOperationException;
import io.haifa.agent.memory.api.MemoryPage;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryRepository;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryService;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Direct CRUD over a {@link MemoryRepository}: owner authorization before every read or write, the sensitive
 * content floor on every write, and injected ids and time.
 */
public final class DefaultMemoryService implements MemoryService {
    public static final String UNAVAILABLE = "MEMORY_UNAVAILABLE";
    public static final String REVISION_STALE = "MEMORY_REVISION_STALE";
    public static final String WRITE_STALE = "MEMORY_WRITE_STALE";

    private final MemoryRepository repository;
    private final IdentifierGenerator ids;
    private final TimeProvider time;

    public DefaultMemoryService(MemoryRepository repository, IdentifierGenerator ids, TimeProvider time) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    @Override
    public Memory put(MemoryDraft draft, MemoryActor actor) {
        Objects.requireNonNull(draft, "draft must not be null");
        requireOwner(actor, draft.scope());
        SensitiveMemoryFilter.requireSafe(draft.subjectKey(), draft.content());
        return repository.upsert(draft, new MemoryId(ids.nextValue()), now());
    }

    @Override
    public Memory update(MemoryId id, long expectedRevision, String content, MemoryActor actor) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Memory current = requireOwned(id, actor);
        SensitiveMemoryFilter.requireSafe(content);
        // Identical content is not a change, and a retried update whose first attempt already committed returns
        // the current state instead of failing.
        if (current.sameContent(content)) return current;
        Optional<Memory> updated = repository.update(id, expectedRevision, content, now());
        if (updated.isPresent()) return updated.orElseThrow();
        Memory latest = requireOwned(id, actor);
        if (latest.sameContent(content)) return latest;
        throw new MemoryOperationException(REVISION_STALE);
    }

    @Override
    public void delete(MemoryId id, long expectedRevision, MemoryActor actor) {
        Objects.requireNonNull(id, "id must not be null");
        requireOwned(id, actor);
        if (!repository.delete(id, expectedRevision, now())) {
            throw new MemoryOperationException(repository.find(id).isPresent() ? REVISION_STALE : UNAVAILABLE);
        }
    }

    @Override
    public Optional<Memory> find(MemoryId id, MemoryActor actor) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        return repository.find(id).filter(memory -> actor.owns(memory.scope()));
    }

    @Override
    public MemoryPage list(MemoryQuery query, MemoryActor actor) {
        Objects.requireNonNull(query, "query must not be null");
        requireOwner(actor, query.scope());
        return repository.list(query);
    }

    @Override
    public int clear(MemoryScope scope, MemoryActor actor) {
        requireOwner(actor, Objects.requireNonNull(scope, "scope must not be null"));
        return repository.clear(scope, now());
    }

    private Memory requireOwned(MemoryId id, MemoryActor actor) {
        return find(id, actor).orElseThrow(() -> new MemoryOperationException(UNAVAILABLE));
    }

    private static void requireOwner(MemoryActor actor, MemoryScope scope) {
        Objects.requireNonNull(actor, "actor must not be null");
        if (!actor.owns(scope)) throw new MemoryOperationException(UNAVAILABLE);
    }

    private Instant now() {
        return TimePrecision.toMilliseconds(time.now());
    }
}
