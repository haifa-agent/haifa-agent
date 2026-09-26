package io.haifa.agent.memory.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryContextRequest;
import io.haifa.agent.memory.api.MemoryDraft;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryOperationException;
import io.haifa.agent.memory.api.MemoryPage;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryRepository;
import io.haifa.agent.memory.api.MemoryRetriever;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryScopeType;
import io.haifa.agent.memory.api.MemorySnippet;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemorySourceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DirectMemoryCrudTest {
    private static final TenantRef TENANT = new TenantRef("tenant-a");
    private static final PrincipalRef OWNER = new PrincipalRef("user-a", "USER");
    private static final PrincipalRef OTHER = new PrincipalRef("user-b", "USER");
    private static final MemoryActor ACTOR = new MemoryActor(TENANT, OWNER);
    private static final MemoryScope USER = MemoryScope.user(TENANT, OWNER);
    private static final MemoryScope AGENT = MemoryScope.agent(TENANT, OWNER, "research-agent");

    private final AtomicLong clock = new AtomicLong(1_000);
    private final AtomicInteger ids = new AtomicInteger();
    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final DefaultMemoryService service = new DefaultMemoryService(
            store, () -> "memory-" + ids.incrementAndGet(), () -> Instant.ofEpochMilli(clock.addAndGet(10)));
    private final DefaultMemoryRetriever retriever = new DefaultMemoryRetriever(store);

    @Test
    void explicitWriteUpdateAndDeleteUseRevisionCompareAndSet() {
        Memory created = service.put(fact(USER, "language", "User prefers Java"), ACTOR);
        assertThat(created.revision()).isEqualTo(1);
        assertThat(service.find(created.id(), ACTOR)).contains(created);

        Memory updated = service.update(created.id(), 1, "User prefers Kotlin", ACTOR);
        assertThat(updated.revision()).isEqualTo(2);
        assertThat(updated.content()).isEqualTo("User prefers Kotlin");
        assertThat(service.update(created.id(), 1, "User prefers Kotlin", ACTOR))
                .as("a retried update returns the committed state")
                .isEqualTo(updated);
        assertCode(() -> service.update(created.id(), 1, "User prefers Rust", ACTOR), "MEMORY_REVISION_STALE");
        assertCode(() -> service.delete(created.id(), 1, ACTOR), "MEMORY_REVISION_STALE");

        service.delete(created.id(), 2, ACTOR);
        assertThat(service.find(created.id(), ACTOR)).isEmpty();
        assertThat(service.list(MemoryQuery.all(USER, 10), ACTOR).items()).isEmpty();
        assertCode(() -> service.delete(created.id(), 3, ACTOR), "MEMORY_UNAVAILABLE");
        assertCode(() -> service.update(created.id(), 3, "again", ACTOR), "MEMORY_UNAVAILABLE");
    }

    @Test
    void retriedMutationsSucceedByIntentWhileOtherRevisionsStillConflict() {
        Memory created = service.put(fact(USER, "editor", "Uses IntelliJ"), ACTOR);
        Memory updated = service.update(created.id(), 1, "Uses VS Code", ACTOR);
        assertThat(service.update(created.id(), 1, " Uses  VS Code ", ACTOR))
                .as("retry with the same revision and content returns the committed state")
                .isEqualTo(updated);
        assertThat(service.update(created.id(), 2, "Uses VS Code", ACTOR))
                .as("identical content at the current revision is not a change")
                .isEqualTo(updated);
        assertCode(() -> service.update(created.id(), 1, "Uses Vim", ACTOR), "MEMORY_REVISION_STALE");
        Memory third = service.update(created.id(), 2, "Uses Vim", ACTOR);
        assertThat(third.revision()).isEqualTo(3);
        // Identical content two revisions later is not this caller's retry.
        assertCode(() -> service.update(created.id(), 1, "Uses Vim", ACTOR), "MEMORY_REVISION_STALE");

        service.delete(created.id(), 3, ACTOR);
        service.delete(created.id(), 3, ACTOR);
        assertCode(() -> service.delete(created.id(), 2, ACTOR), "MEMORY_UNAVAILABLE");
        assertCode(() -> service.delete(created.id(), 4, ACTOR), "MEMORY_UNAVAILABLE");
        assertCode(() -> service.delete(created.id(), 3, new MemoryActor(TENANT, OTHER)), "MEMORY_UNAVAILABLE");
        assertCode(() -> service.update(created.id(), 3, "Uses Vim", ACTOR), "MEMORY_UNAVAILABLE");

        Memory revived = service.put(fact(USER, "editor", "Uses Emacs"), ACTOR);
        assertThat(revived.id()).isEqualTo(created.id());
        assertCode(() -> service.delete(created.id(), 3, ACTOR), "MEMORY_REVISION_STALE");

        assertThat(service.clear(USER, ACTOR)).isEqualTo(1);
        assertThat(service.clear(USER, ACTOR))
                .as("a retried clear keeps the scope empty")
                .isZero();
        assertCode(() -> service.delete(created.id(), revived.revision(), ACTOR), "MEMORY_UNAVAILABLE");
    }

    @Test
    void mutationsWhoseResponseWasLostCanBeRetriedWithTheSameRevision() {
        LosingResponses lossy = new LosingResponses(store);
        DefaultMemoryService unreliable = new DefaultMemoryService(
                lossy, () -> "memory-" + ids.incrementAndGet(), () -> Instant.ofEpochMilli(clock.addAndGet(10)));
        Memory created = unreliable.put(fact(USER, "city", "Lives in Hangzhou"), ACTOR);

        lossy.loseNextResponse();
        assertThatThrownBy(() -> unreliable.update(created.id(), 1, "Lives in Shanghai", ACTOR))
                .hasMessage("response lost");
        Memory retried = unreliable.update(created.id(), 1, "Lives in Shanghai", ACTOR);
        assertThat(retried.revision()).isEqualTo(2);
        assertThat(retried.content()).isEqualTo("Lives in Shanghai");

        lossy.loseNextResponse();
        assertThatThrownBy(() -> unreliable.delete(created.id(), 2, ACTOR)).hasMessage("response lost");
        unreliable.delete(created.id(), 2, ACTOR);
        assertThat(unreliable.find(created.id(), ACTOR)).isEmpty();
        assertThat(unreliable.list(MemoryQuery.all(USER, 10), ACTOR).items()).isEmpty();
    }

    @Test
    void textMatchFoldsUnicodeCase() {
        service.put(fact(USER, "fruit", "Äpfel und Birnen"), ACTOR);
        service.put(fact(USER, "friend", "ΣΟΦΙΑ drinks tea"), ACTOR);
        service.put(fact(USER, "drink", "用户喜欢喝绿茶"), ACTOR);
        service.put(fact(USER, "physics", "Kelvin is the unit of temperature"), ACTOR);

        assertThat(matching("äpfel")).containsExactly("Äpfel und Birnen");
        assertThat(matching("ÄPFEL")).containsExactly("Äpfel und Birnen");
        assertThat(matching("σοφια")).containsExactly("ΣΟΦΙΑ drinks tea");
        assertThat(matching("绿茶")).containsExactly("用户喜欢喝绿茶");
        assertThat(matching("kelvin")).containsExactly("Kelvin is the unit of temperature");
        assertThat(matching("kiwi")).isEmpty();
    }

    @Test
    void putWithSameSubjectReplacesAndDuplicateWritesDoNotGrowTheStore() {
        Memory first = service.put(fact(USER, "Editor", "Uses IntelliJ"), ACTOR);
        Memory duplicate = service.put(fact(USER, "editor", "  Uses   IntelliJ "), ACTOR);
        assertThat(duplicate).isEqualTo(first);

        Memory replaced = service.put(fact(USER, "editor", "Uses VS Code"), ACTOR);
        assertThat(replaced.id()).isEqualTo(first.id());
        assertThat(replaced.revision()).isEqualTo(2);
        for (int attempt = 0; attempt < 5; attempt++) service.put(fact(USER, "editor", "Uses VS Code"), ACTOR);

        assertThat(service.list(MemoryQuery.all(USER, 10), ACTOR).items())
                .singleElement()
                .satisfies(memory -> {
                    assertThat(memory.revision()).isEqualTo(2);
                    assertThat(memory.content()).isEqualTo("Uses VS Code");
                });
    }

    @Test
    void agentAndUserBucketsDoNotLeakIntoEachOtherOrToOtherAgents() {
        service.put(fact(USER, "timezone", "User lives in UTC+8"), ACTOR);
        service.put(fact(AGENT, "timezone", "Research agent cites sources in footnotes"), ACTOR);

        assertThat(contents(service.list(MemoryQuery.all(USER, 10), ACTOR).items()))
                .containsExactly("User lives in UTC+8");
        assertThat(contents(service.list(MemoryQuery.all(AGENT, 10), ACTOR).items()))
                .containsExactly("Research agent cites sources in footnotes");
        assertThat(service.list(MemoryQuery.all(MemoryScope.agent(TENANT, OWNER, "writer-agent"), 10), ACTOR)
                        .items())
                .isEmpty();

        assertThat(texts(recall("research-agent", "timezone footnotes")))
                .containsExactlyInAnyOrder("User lives in UTC+8", "Research agent cites sources in footnotes");
        assertThat(texts(recall("writer-agent", "timezone footnotes"))).containsExactly("User lives in UTC+8");

        service.clear(AGENT, ACTOR);
        assertThat(texts(recall("research-agent", "timezone footnotes"))).containsExactly("User lives in UTC+8");
    }

    @Test
    void deletedAndUpdatedMemoriesAreReflectedByTheNextRecall() {
        Memory memory = service.put(fact(USER, "pet", "User has a cat named Miso"), ACTOR);
        assertThat(texts(recall("agent", "tell me about my cat"))).containsExactly("User has a cat named Miso");

        Memory updated = service.update(memory.id(), 1, "User has a cat named Tofu", ACTOR);
        assertThat(texts(recall("agent", "tell me about my cat"))).containsExactly("User has a cat named Tofu");

        service.delete(updated.id(), updated.revision(), ACTOR);
        assertThat(recall("agent", "tell me about my cat")).isEmpty();
    }

    @Test
    void lateWritesObservedBeforeClearOrDeleteDoNotResurrectContent() {
        Instant extractionStarted = Instant.ofEpochMilli(clock.get());
        Memory memory = service.put(fact(USER, "project", "Works on Haifa"), ACTOR);
        service.delete(memory.id(), memory.revision(), ACTOR);
        assertCode(
                () -> service.put(observed(USER, "project", "Works on Haifa", extractionStarted), ACTOR),
                "MEMORY_WRITE_STALE");

        Instant beforeClear = Instant.ofEpochMilli(clock.get());
        service.put(fact(USER, "city", "Lives in Hangzhou"), ACTOR);
        assertThat(service.clear(USER, ACTOR)).isEqualTo(1);
        assertCode(
                () -> service.put(observed(USER, "city", "Lives in Hangzhou", beforeClear), ACTOR),
                "MEMORY_WRITE_STALE");
        assertCode(
                () -> service.put(observed(USER, "new-subject", "Never seen before", beforeClear), ACTOR),
                "MEMORY_WRITE_STALE");
        assertThat(service.list(MemoryQuery.all(USER, 10), ACTOR).items()).isEmpty();
        assertThat(recall("agent", "hangzhou haifa")).isEmpty();

        Memory fresh =
                service.put(observed(USER, "city", "Lives in Shanghai", Instant.ofEpochMilli(clock.get() + 1)), ACTOR);
        assertThat(fresh.content()).isEqualTo("Lives in Shanghai");
    }

    @Test
    void recallStaysInsideCountAndTokenBudget() {
        for (int index = 0; index < 20; index++) {
            service.put(fact(USER, "topic-" + index, "budget topic " + index + " " + "x".repeat(200)), ACTOR);
        }
        List<MemorySnippet> small =
                retriever.contextFor(request("agent", "budget topic", 120)).snippets();
        assertThat(small.stream().mapToInt(MemorySnippet::estimatedTokens).sum())
                .isLessThanOrEqualTo(120);
        assertThat(small).hasSizeLessThanOrEqualTo(2);
        List<MemorySnippet> large =
                retriever.contextFor(request("agent", "budget topic", 100_000)).snippets();
        assertThat(large).hasSize(DefaultMemoryRetriever.MAX_ITEMS);
    }

    @Test
    void recallCanBeDisabledPerRunOrAgent() {
        service.put(fact(USER, "food", "User likes noodles"), ACTOR);
        MemoryRetriever gated = retriever.onlyWhen(request ->
                !request.agentId().equals("quiet-agent") && !request.runId().equals("run-without-memory"));

        assertThat(gated.contextFor(request("agent", "noodles", 1_000)).snippets())
                .hasSize(1);
        assertThat(gated.contextFor(request("quiet-agent", "noodles", 1_000)).snippets())
                .isEmpty();
        assertThat(gated.contextFor(new MemoryContextRequest(
                                TENANT, OWNER, "run-without-memory", "session-1", "agent", "noodles", 1_000))
                        .snippets())
                .isEmpty();
        assertThat(MemoryRetriever.none()
                        .contextFor(request("agent", "noodles", 1_000))
                        .snippets())
                .isEmpty();
    }

    @Test
    void zeroScoreQueryRecallsMemoriesUpToCapacity() {
        service.put(fact(USER, "city", "Lives in Hangzhou"), ACTOR);
        service.put(fact(USER, "theme", "Prefers dark mode"), ACTOR);
        service.put(fact(USER, "lang", "Speaks Mandarin and English"), ACTOR);

        // Query has terms that match none of the memories; score is 0 for all of them
        List<MemorySnippet> snippets = retriever
                .contextFor(request("agent", "what is the weather today", 10_000))
                .snippets();
        assertThat(snippets).hasSize(3);
        assertThat(texts(snippets))
                .containsExactlyInAnyOrder("Lives in Hangzhou", "Prefers dark mode", "Speaks Mandarin and English");
    }

    @Test
    void relevanceTakesPriorityAndRecencyFillsRemainingSlots() {
        for (int index = 0; index < 20; index++) {
            service.put(fact(USER, "note-" + index, "general background note " + index), ACTOR);
        }
        service.put(fact(USER, "framework", "User loves React and Redux"), ACTOR);
        service.put(fact(USER, "ui", "User maintains React components"), ACTOR);

        List<MemorySnippet> snippets = retriever
                .contextFor(request("agent", "tell me about react", 100_000))
                .snippets();
        assertThat(snippets).hasSize(DefaultMemoryRetriever.MAX_ITEMS);
        assertThat(texts(snippets)).contains("User loves React and Redux", "User maintains React components");
    }

    @Test
    void promptCacheOrderingIsDeterministicAcrossDifferentQueries() {
        service.put(fact(USER, "city", "Lives in Hangzhou"), ACTOR);
        service.put(fact(USER, "theme", "Prefers dark mode"), ACTOR);
        service.put(fact(USER, "food", "Likes ramen and dumplings"), ACTOR);

        List<MemoryId> orderFromThemeQuery =
                retriever.contextFor(request("agent", "dark mode settings", 10_000)).snippets().stream()
                        .map(MemorySnippet::id)
                        .toList();
        List<MemoryId> orderFromCityQuery =
                retriever.contextFor(request("agent", "hangzhou travel guide", 10_000)).snippets().stream()
                        .map(MemorySnippet::id)
                        .toList();
        List<MemoryId> orderFromUnrelatedQuery =
                retriever.contextFor(request("agent", "quantum computing physics", 10_000)).snippets().stream()
                        .map(MemorySnippet::id)
                        .toList();

        assertThat(orderFromThemeQuery).hasSize(3);
        assertThat(orderFromThemeQuery).isEqualTo(orderFromCityQuery);
        assertThat(orderFromThemeQuery).isEqualTo(orderFromUnrelatedQuery);
    }

    @Test
    void sensitiveContentIsRejectedBeforeAnyWrite() {
        for (String secret : List.of(
                "my password is hunter2",
                "OpenAI api key sk-test",
                "card 4111 1111 1111 1111",
                "cvv 123",
                "我的密码是 123456",
                "Bearer abc.def")) {
            assertCode(() -> service.put(fact(USER, "secret", secret), ACTOR), "MEMORY_CONTENT_SENSITIVE");
        }
        Memory memory = service.put(fact(USER, "hobby", "Plays chess"), ACTOR);
        assertCode(() -> service.update(memory.id(), 1, "the secret token is abc", ACTOR), "MEMORY_CONTENT_SENSITIVE");
        assertThat(service.list(MemoryQuery.all(USER, 10), ACTOR).items()).containsExactly(memory);
    }

    @Test
    void foreignTenantOrOwnerScopesAreRejected() {
        Memory memory = service.put(fact(USER, "color", "Likes green"), ACTOR);
        MemoryActor other = new MemoryActor(TENANT, OTHER);
        MemoryActor otherTenant = new MemoryActor(new TenantRef("tenant-b"), OWNER);

        assertCode(() -> service.put(fact(USER, "color", "Likes red"), other), "MEMORY_UNAVAILABLE");
        assertCode(() -> service.put(fact(USER, "color", "Likes red"), otherTenant), "MEMORY_UNAVAILABLE");
        assertThat(service.find(memory.id(), other)).isEmpty();
        assertCode(() -> service.update(memory.id(), 1, "Likes red", other), "MEMORY_UNAVAILABLE");
        assertCode(() -> service.delete(memory.id(), 1, otherTenant), "MEMORY_UNAVAILABLE");
        assertCode(() -> service.list(MemoryQuery.all(USER, 10), other), "MEMORY_UNAVAILABLE");
        assertCode(() -> service.clear(USER, other), "MEMORY_UNAVAILABLE");
        assertThatThrownBy(() -> new MemoryScope(TENANT, OWNER, MemoryScopeType.USER, "user-b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.find(memory.id(), ACTOR)).contains(memory);
    }

    @Test
    void listSupportsBoundedTextMatchKindsAndCursorPagination() {
        service.put(fact(USER, "drink", "Likes green tea"), ACTOR);
        service.put(
                new MemoryDraft(
                        USER,
                        MemoryKind.PREFERENCE,
                        "tone",
                        "Prefers concise answers",
                        Optional.of(new MemorySourceRef(MemorySourceType.MESSAGE, "message-1")),
                        Optional.empty()),
                ACTOR);
        service.put(fact(USER, "sport", "Plays tennis"), ACTOR);

        assertThat(contents(
                        service.list(new MemoryQuery(USER, Set.of(), Optional.of("TEA"), Optional.empty(), 10), ACTOR)
                                .items()))
                .containsExactly("Likes green tea");
        assertThat(service.list(
                                new MemoryQuery(
                                        USER, Set.of(MemoryKind.PREFERENCE), Optional.empty(), Optional.empty(), 10),
                                ACTOR)
                        .items())
                .singleElement()
                .satisfies(memory -> assertThat(memory.source())
                        .contains(new MemorySourceRef(MemorySourceType.MESSAGE, "message-1")));

        var first = service.list(MemoryQuery.all(USER, 2), ACTOR);
        assertThat(first.items()).hasSize(2);
        var second = service.list(new MemoryQuery(USER, Set.of(), Optional.empty(), first.nextCursor(), 2), ACTOR);
        assertThat(second.items()).hasSize(1);
        assertThat(second.nextCursor()).isEmpty();
    }

    private List<String> matching(String text) {
        return contents(service.list(new MemoryQuery(USER, Set.of(), Optional.of(text), Optional.empty(), 10), ACTOR)
                .items());
    }

    private List<MemorySnippet> recall(String agentId, String query) {
        return retriever.contextFor(request(agentId, query, 4_000)).snippets();
    }

    private static MemoryContextRequest request(String agentId, String query, int budget) {
        return new MemoryContextRequest(TENANT, OWNER, "run-1", "session-1", agentId, query, budget);
    }

    private static MemoryDraft fact(MemoryScope scope, String subject, String content) {
        return MemoryDraft.of(scope, MemoryKind.FACT, subject, content);
    }

    private static MemoryDraft observed(MemoryScope scope, String subject, String content, Instant observedAt) {
        return new MemoryDraft(scope, MemoryKind.FACT, subject, content, Optional.empty(), Optional.of(observedAt));
    }

    private static List<String> contents(List<Memory> memories) {
        return memories.stream().map(Memory::content).toList();
    }

    private static List<String> texts(List<MemorySnippet> snippets) {
        return snippets.stream().map(MemorySnippet::text).toList();
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(MemoryOperationException.class)
                .extracting(error -> ((MemoryOperationException) error).code())
                .isEqualTo(code);
    }

    /** Commits the next update or delete and then fails, as when the response is lost on the way to the caller. */
    private static final class LosingResponses implements MemoryRepository {
        private final MemoryRepository delegate;
        private boolean loseNext;

        private LosingResponses(MemoryRepository delegate) {
            this.delegate = delegate;
        }

        void loseNextResponse() {
            loseNext = true;
        }

        @Override
        public Memory upsert(MemoryDraft draft, MemoryId newId, Instant now) {
            return delegate.upsert(draft, newId, now);
        }

        @Override
        public Optional<Memory> find(MemoryId id) {
            return delegate.find(id);
        }

        @Override
        public Optional<Memory> update(MemoryId id, long expectedRevision, String content, Instant now) {
            return lost(delegate.update(id, expectedRevision, content, now));
        }

        @Override
        public boolean delete(MemoryId id, long expectedRevision, Instant now) {
            return lost(delegate.delete(id, expectedRevision, now));
        }

        @Override
        public Optional<MemoryScope> deletedFrom(MemoryId id, long expectedRevision) {
            return delegate.deletedFrom(id, expectedRevision);
        }

        @Override
        public MemoryPage list(MemoryQuery query) {
            return delegate.list(query);
        }

        @Override
        public int clear(MemoryScope scope, Instant now) {
            return delegate.clear(scope, now);
        }

        @Override
        public List<Memory> recent(List<MemoryScope> scopes, int limit) {
            return delegate.recent(scopes, limit);
        }

        private <T> T lost(T committed) {
            if (!loseNext) return committed;
            loseNext = false;
            throw new IllegalStateException("response lost");
        }
    }
}
