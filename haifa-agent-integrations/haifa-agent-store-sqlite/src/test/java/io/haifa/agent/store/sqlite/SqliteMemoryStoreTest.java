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
import io.haifa.agent.memory.api.MemoryRepository;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemorySnippet;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemorySourceType;
import io.haifa.agent.memory.core.DefaultMemoryRetriever;
import io.haifa.agent.memory.core.DefaultMemoryService;
import io.haifa.agent.memory.core.InMemoryMemoryStore;
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

    @Test
    void textMatchFoldsUnicodeCaseLikeTheInMemoryStore() {
        onBothStores((store, service) -> {
            service.put(fact(USER, "fruit", "Äpfel und Birnen"), ACTOR);
            service.put(fact(USER, "friend", "ΣΟΦΙΑ drinks tea"), ACTOR);
            service.put(fact(USER, "drink", "用户喜欢喝绿茶"), ACTOR);
            service.put(fact(USER, "physics", "Kelvin is the unit of temperature"), ACTOR);
            service.put(fact(USER, "ÜBER", "Subject keys are folded as well"), ACTOR);

            assertThat(matching(service, "äpfel", 10)).as(store).containsExactly("Äpfel und Birnen");
            assertThat(matching(service, "ÄPFEL", 10)).as(store).containsExactly("Äpfel und Birnen");
            assertThat(matching(service, "BIRNEN", 10)).as(store).containsExactly("Äpfel und Birnen");
            assertThat(matching(service, "σοφια", 10)).as(store).containsExactly("ΣΟΦΙΑ drinks tea");
            assertThat(matching(service, "绿茶", 10)).as(store).containsExactly("用户喜欢喝绿茶");
            assertThat(matching(service, "kelvin", 10)).as(store).containsExactly("Kelvin is the unit of temperature");
            assertThat(matching(service, "Über", 10)).as(store).containsExactly("Subject keys are folded as well");
            assertThat(matching(service, "kiwi", 10)).as(store).isEmpty();
        });
    }

    @Test
    void unicodeTextMatchPagesAcrossScanBatchesLikeTheInMemoryStore() {
        // SQLite reads four candidate rows per batch, so each page must cross several batches of non-matching rows.
        onBothStores(4, (store, service) -> {
            service.put(fact(USER, "oldest", "Äpfel from the market"), ACTOR);
            for (int index = 0; index < 10; index++)
                service.put(fact(USER, "noise-a" + index, "Noise " + index), ACTOR);
            service.put(fact(USER, "middle", "ÄPFEL in the cellar"), ACTOR);
            for (int index = 0; index < 10; index++)
                service.put(fact(USER, "noise-b" + index, "Noise " + index), ACTOR);
            service.put(fact(USER, "newest", "äpfel for dessert"), ACTOR);

            var first = service.list(new MemoryQuery(USER, Set.of(), Optional.of("äPFEL"), Optional.empty(), 2), ACTOR);
            assertThat(first.items())
                    .as(store)
                    .extracting(Memory::content)
                    .containsExactly("äpfel for dessert", "ÄPFEL in the cellar");
            assertThat(first.nextCursor()).as(store).isPresent();
            var second =
                    service.list(new MemoryQuery(USER, Set.of(), Optional.of("äPFEL"), first.nextCursor(), 2), ACTOR);
            assertThat(second.items()).as(store).extracting(Memory::content).containsExactly("Äpfel from the market");
            assertThat(second.nextCursor()).as(store).isEmpty();
            var exact = service.list(new MemoryQuery(USER, Set.of(), Optional.of("äpfel"), Optional.empty(), 3), ACTOR);
            assertThat(exact.items()).as(store).hasSize(3);
            assertThat(exact.nextCursor()).as(store).isEmpty();
        });
    }

    @Test
    void retriedMutationsSucceedByIntentLikeTheInMemoryStore() {
        onBothStores((store, service) -> {
            Memory created = service.put(fact(USER, "editor", "Uses IntelliJ"), ACTOR);
            Memory updated = service.update(created.id(), 1, "Uses VS Code", ACTOR);
            assertThat(service.update(created.id(), 1, "Uses VS Code", ACTOR))
                    .as(store)
                    .isEqualTo(updated);
            assertThatThrownBy(() -> service.update(created.id(), 1, "Uses Vim", ACTOR))
                    .as(store)
                    .extracting(error -> ((MemoryOperationException) error).code())
                    .isEqualTo("MEMORY_REVISION_STALE");

            service.delete(created.id(), 2, ACTOR);
            service.delete(created.id(), 2, ACTOR);
            assertThatThrownBy(() -> service.delete(created.id(), 1, ACTOR))
                    .as(store)
                    .extracting(error -> ((MemoryOperationException) error).code())
                    .isEqualTo("MEMORY_UNAVAILABLE");
            assertThatThrownBy(() -> service.delete(
                            created.id(), 2, new MemoryActor(TENANT, new PrincipalRef("user-b", "user"))))
                    .as(store)
                    .extracting(error -> ((MemoryOperationException) error).code())
                    .isEqualTo("MEMORY_UNAVAILABLE");

            service.put(fact(USER, "city", "Lives in Hangzhou"), ACTOR);
            assertThat(service.clear(USER, ACTOR)).as(store).isEqualTo(1);
            assertThat(service.clear(USER, ACTOR)).as(store).isZero();
            assertThatThrownBy(() -> service.delete(created.id(), 2, ACTOR))
                    .as(store + ": clear removes the tombstone")
                    .extracting(error -> ((MemoryOperationException) error).code())
                    .isEqualTo("MEMORY_UNAVAILABLE");
        });
    }

    private void onBothStores(java.util.function.BiConsumer<String, DefaultMemoryService> check) {
        onBothStores(0, check);
    }

    private void onBothStores(int textScanBatch, java.util.function.BiConsumer<String, DefaultMemoryService> check) {
        check.accept("in-memory", service(new InMemoryMemoryStore()));
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            check.accept(
                    "sqlite",
                    service(
                            textScanBatch == 0
                                    ? new SqliteMemoryStore(foundation.unitOfWork())
                                    : new SqliteMemoryStore(foundation.unitOfWork(), textScanBatch)));
        }
    }

    private static List<String> matching(DefaultMemoryService service, String text, int limit) {
        return service
                .list(new MemoryQuery(USER, Set.of(), Optional.of(text), Optional.empty(), limit), ACTOR)
                .items()
                .stream()
                .map(Memory::content)
                .toList();
    }

    private Memory kept(DefaultMemoryService service) {
        return service.list(MemoryQuery.all(USER, 10), ACTOR).items().getFirst();
    }

    private DefaultMemoryService service(SqliteStoreFoundation foundation) {
        return service(new SqliteMemoryStore(foundation.unitOfWork()));
    }

    private DefaultMemoryService service(MemoryRepository repository) {
        return new DefaultMemoryService(
                repository, () -> "memory-" + ids.incrementAndGet(), () -> Instant.ofEpochMilli(clock.addAndGet(10)));
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
