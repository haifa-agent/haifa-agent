package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryContextRequest;
import io.haifa.agent.memory.api.MemoryDraft;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryOperationException;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemorySnippet;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemorySourceType;
import io.haifa.agent.memory.core.DefaultMemoryRetriever;
import io.haifa.agent.memory.core.DefaultMemoryService;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMemoryStoreTest {
    private static final TenantRef TENANT = new TenantRef("tenant-a");
    private static final PrincipalRef OWNER = new PrincipalRef("user-a", "user");
    private static final MemoryActor ACTOR = new MemoryActor(TENANT, OWNER);
    private static final MemoryScope USER = MemoryScope.user(TENANT, OWNER);
    private static final MemoryScope AGENT = MemoryScope.agent(TENANT, OWNER, "research-agent");

    @TempDir
    Path directory;

    private final AtomicLong clock = new AtomicLong(SqliteTestSupport.NOW.toEpochMilli());
    private final AtomicInteger ids = new AtomicInteger();

    @Test
    void directCrudSurvivesRestartAndDeletedContentIsErased() throws Exception {
        Memory kept;
        Memory deleted;
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            DefaultMemoryService service = service(foundation);
            kept = service.put(
                    new MemoryDraft(
                            USER,
                            MemoryKind.PREFERENCE,
                            "language",
                            "Prefers Java",
                            Optional.of(new MemorySourceRef(MemorySourceType.MESSAGE, "message-1")),
                            Optional.empty()),
                    ACTOR);
            kept = service.update(kept.id(), 1, "Prefers Kotlin", ACTOR);
            assertThat(service.put(MemoryDraft.of(USER, MemoryKind.PREFERENCE, "language", "Prefers Kotlin"), ACTOR)
                            .revision())
                    .as("duplicate put keeps the revision")
                    .isEqualTo(2);
            deleted = service.put(fact(USER, "temporary", "Temporary fact to delete"), ACTOR);
            service.delete(deleted.id(), deleted.revision(), ACTOR);
            assertThatThrownBy(() -> service.update(kept(service).id(), 1, "Prefers Rust", ACTOR))
                    .isInstanceOf(MemoryOperationException.class)
                    .extracting(error -> ((MemoryOperationException) error).code())
                    .isEqualTo("MEMORY_REVISION_STALE");
        }

        try (SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory)) {
            DefaultMemoryService service = service(reopened);
            Memory reloaded = service.find(kept.id(), ACTOR).orElseThrow();
            assertThat(reloaded).isEqualTo(kept);
            assertThat(reloaded.source()).contains(new MemorySourceRef(MemorySourceType.MESSAGE, "message-1"));
            assertThat(service.find(deleted.id(), ACTOR)).isEmpty();
            assertThat(service.list(MemoryQuery.all(USER, 10), ACTOR).items()).containsExactly(kept);
            try (Connection connection = reopened.connections().openConnection()) {
                assertThat(queryLong(
                                connection,
                                "SELECT COUNT(*) FROM memory_record WHERE memory_id='"
                                        + deleted.id().value() + "' AND content IS NULL AND deleted_at IS NOT NULL"))
                        .isEqualTo(1);
            }
        }
    }

    @Test
    void clearWatermarkSurvivesRestartAndRejectsLateWrites() {
        Instant beforeClear;
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            DefaultMemoryService service = service(foundation);
            service.put(fact(AGENT, "style", "Cites sources in footnotes"), ACTOR);
            service.put(fact(USER, "city", "Lives in Hangzhou"), ACTOR);
            beforeClear = Instant.ofEpochMilli(clock.get());
            assertThat(service.clear(AGENT, ACTOR)).isEqualTo(1);
        }
        try (SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory)) {
            DefaultMemoryService service = service(reopened);
            assertThatThrownBy(() -> service.put(
                            new MemoryDraft(
                                    AGENT,
                                    MemoryKind.FACT,
                                    "style",
                                    "Cites sources in footnotes",
                                    Optional.empty(),
                                    Optional.of(beforeClear)),
                            ACTOR))
                    .isInstanceOf(MemoryOperationException.class)
                    .extracting(error -> ((MemoryOperationException) error).code())
                    .isEqualTo("MEMORY_WRITE_STALE");
            assertThat(service.list(MemoryQuery.all(AGENT, 10), ACTOR).items()).isEmpty();
            assertThat(service.list(MemoryQuery.all(USER, 10), ACTOR).items()).hasSize(1);

            DefaultMemoryRetriever retriever = new DefaultMemoryRetriever(new SqliteMemoryStore(reopened.unitOfWork()));
            List<MemorySnippet> snippets = retriever
                    .contextFor(new MemoryContextRequest(
                            TENANT, OWNER, "run-1", "session-1", "research-agent", "footnotes hangzhou", 1_000))
                    .snippets();
            assertThat(snippets).extracting(MemorySnippet::text).containsExactly("Lives in Hangzhou");
        }
    }

    @Test
    void listFiltersByTextAndKindInsideOneOwnerScope() {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            DefaultMemoryService service = service(foundation);
            service.put(fact(USER, "drink", "Likes Green Tea"), ACTOR);
            service.put(MemoryDraft.of(USER, MemoryKind.PREFERENCE, "tone", "Prefers concise answers"), ACTOR);
            service.put(fact(AGENT, "drink", "Agent note about tea"), ACTOR);
            MemoryActor other = new MemoryActor(TENANT, new PrincipalRef("user-b", "user"));

            assertThat(service.list(
                                    new MemoryQuery(USER, Set.of(), Optional.of("green tea"), Optional.empty(), 10),
                                    ACTOR)
                            .items())
                    .extracting(Memory::content)
                    .containsExactly("Likes Green Tea");
            assertThat(service.list(
                                    new MemoryQuery(
                                            USER,
                                            Set.of(MemoryKind.PREFERENCE),
                                            Optional.empty(),
                                            Optional.empty(),
                                            10),
                                    ACTOR)
                            .items())
                    .extracting(Memory::content)
                    .containsExactly("Prefers concise answers");
            var firstPage = service.list(MemoryQuery.all(USER, 1), ACTOR);
            assertThat(firstPage.nextCursor()).isPresent();
            assertThat(service.list(new MemoryQuery(USER, Set.of(), Optional.empty(), firstPage.nextCursor(), 1), ACTOR)
                            .items())
                    .hasSize(1)
                    .doesNotContainAnyElementsOf(firstPage.items());
            assertThatThrownBy(() -> service.list(MemoryQuery.all(USER, 10), other))
                    .isInstanceOf(MemoryOperationException.class);
        }
    }

    private Memory kept(DefaultMemoryService service) {
        return service.list(MemoryQuery.all(USER, 10), ACTOR).items().getFirst();
    }

    private DefaultMemoryService service(SqliteStoreFoundation foundation) {
        return new DefaultMemoryService(
                new SqliteMemoryStore(foundation.unitOfWork()),
                () -> "memory-" + ids.incrementAndGet(),
                () -> Instant.ofEpochMilli(clock.addAndGet(10)));
    }

    private static MemoryDraft fact(MemoryScope scope, String subject, String content) {
        return MemoryDraft.of(scope, MemoryKind.FACT, subject, content);
    }

    private static long queryLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
