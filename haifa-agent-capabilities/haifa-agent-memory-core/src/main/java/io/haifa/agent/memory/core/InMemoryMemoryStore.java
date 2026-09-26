package io.haifa.agent.memory.core;

import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryCursorCodec;
import io.haifa.agent.memory.api.MemoryDraft;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryOperationException;
import io.haifa.agent.memory.api.MemoryPage;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryRepository;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemorySourceRef;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe in-memory Memory repository for tests and local runtime assembly. */
public final class InMemoryMemoryStore implements MemoryRepository, AutoCloseable {
    private static final Comparator<Memory> NEWEST_FIRST = Comparator.comparing(Memory::updatedAt)
            .reversed()
            .thenComparing(memory -> memory.id().value(), Comparator.reverseOrder());

    private final Map<MemoryId, Row> rows = new LinkedHashMap<>();
    private final Map<MemoryScope, Instant> clearWatermarks = new HashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public synchronized Memory upsert(MemoryDraft draft, MemoryId newId, Instant now) {
        requireOpen();
        Instant watermark = clearWatermarks.get(draft.scope());
        if (watermark != null && draft.observedNoLaterThan(watermark)) throw stale();
        Optional<Row> existing = rows.values().stream()
                .filter(row -> row.scope.equals(draft.scope())
                        && row.kind == draft.kind()
                        && row.subjectKey.equals(draft.subjectKey()))
                .findFirst();
        if (existing.isEmpty()) {
            Row row = new Row(
                    newId,
                    draft.scope(),
                    draft.kind(),
                    draft.subjectKey(),
                    1,
                    draft.content(),
                    draft.source(),
                    now,
                    now,
                    null);
            rows.put(newId, row);
            return row.memory();
        }
        Row row = existing.orElseThrow();
        if (row.deletedAt != null) {
            if (draft.observedNoLaterThan(row.deletedAt)) throw stale();
            row.revive(draft.content(), draft.source(), now);
            return row.memory();
        }
        if (row.memory().sameContent(draft.content())) return row.memory();
        row.replace(draft.content(), draft.source(), now);
        return row.memory();
    }

    @Override
    public synchronized Optional<Memory> find(MemoryId id) {
        requireOpen();
        return live(id).map(Row::memory);
    }

    @Override
    public synchronized Optional<Memory> update(MemoryId id, long expectedRevision, String content, Instant now) {
        requireOpen();
        Optional<Row> row = live(id).filter(value -> value.revision == expectedRevision);
        row.ifPresent(value -> value.replace(content, value.source, now));
        return row.map(Row::memory);
    }

    @Override
    public synchronized boolean delete(MemoryId id, long expectedRevision, Instant now) {
        requireOpen();
        Optional<Row> row = live(id).filter(value -> value.revision == expectedRevision);
        row.ifPresent(value -> value.delete(now));
        return row.isPresent();
    }

    @Override
    public synchronized MemoryPage list(MemoryQuery query) {
        requireOpen();
        var after = query.after().map(MemoryCursorCodec::decode);
        List<Memory> ordered = liveMemories()
                .filter(memory -> memory.scope().equals(query.scope()))
                .filter(query::matches)
                .filter(memory -> after.map(position -> memory.updatedAt().isBefore(position.updatedAt())
                                || (memory.updatedAt().equals(position.updatedAt())
                                        && memory.id().value().compareTo(position.logicalId()) < 0))
                        .orElse(true))
                .sorted(NEWEST_FIRST)
                .limit((long) query.limit() + 1)
                .toList();
        boolean more = ordered.size() > query.limit();
        List<Memory> items = more ? ordered.subList(0, query.limit()) : ordered;
        return new MemoryPage(items, more ? Optional.of(cursor(items.get(items.size() - 1))) : Optional.empty());
    }

    @Override
    public synchronized int clear(MemoryScope scope, Instant now) {
        requireOpen();
        long live = rows.values().stream()
                .filter(row -> row.scope.equals(scope) && row.deletedAt == null)
                .count();
        rows.values().removeIf(row -> row.scope.equals(scope));
        clearWatermarks.merge(scope, now, (left, right) -> left.isAfter(right) ? left : right);
        return Math.toIntExact(live);
    }

    @Override
    public synchronized List<Memory> recent(List<MemoryScope> scopes, int limit) {
        requireOpen();
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return liveMemories()
                .filter(memory -> scopes.contains(memory.scope()))
                .sorted(NEWEST_FIRST)
                .limit(limit)
                .toList();
    }

    @Override
    public void close() {
        closed.set(true);
    }

    static io.haifa.agent.memory.api.MemoryPageCursor cursor(Memory memory) {
        return MemoryCursorCodec.encode(memory.updatedAt(), memory.id().value(), memory.revision());
    }

    private java.util.stream.Stream<Memory> liveMemories() {
        return rows.values().stream().filter(row -> row.deletedAt == null).map(Row::memory);
    }

    private Optional<Row> live(MemoryId id) {
        return Optional.ofNullable(rows.get(Objects.requireNonNull(id, "id must not be null")))
                .filter(row -> row.deletedAt == null);
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("MEMORY_STORE_CLOSED");
    }

    private static MemoryOperationException stale() {
        return new MemoryOperationException(DefaultMemoryService.WRITE_STALE);
    }

    private static final class Row {
        private final MemoryId id;
        private final MemoryScope scope;
        private final MemoryKind kind;
        private final String subjectKey;
        private long revision;
        private String content;
        private Optional<MemorySourceRef> source;
        private Instant createdAt;
        private Instant updatedAt;
        private Instant deletedAt;

        private Row(
                MemoryId id,
                MemoryScope scope,
                MemoryKind kind,
                String subjectKey,
                long revision,
                String content,
                Optional<MemorySourceRef> source,
                Instant createdAt,
                Instant updatedAt,
                Instant deletedAt) {
            this.id = id;
            this.scope = scope;
            this.kind = kind;
            this.subjectKey = subjectKey;
            this.revision = revision;
            this.content = content;
            this.source = source;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.deletedAt = deletedAt;
        }

        private void replace(String newContent, Optional<MemorySourceRef> newSource, Instant now) {
            content = newContent;
            source = newSource;
            revision++;
            updatedAt = later(now);
        }

        private void revive(String newContent, Optional<MemorySourceRef> newSource, Instant now) {
            replace(newContent, newSource, now);
            createdAt = updatedAt;
            deletedAt = null;
        }

        private void delete(Instant now) {
            content = null;
            source = Optional.empty();
            revision++;
            updatedAt = later(now);
            deletedAt = updatedAt;
        }

        private Instant later(Instant now) {
            return now.isBefore(updatedAt) ? updatedAt : now;
        }

        private Memory memory() {
            return new Memory(id, revision, scope, kind, subjectKey, content, source, createdAt, updatedAt);
        }
    }
}
