package io.haifa.agent.memory.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.memory.api.DerivedTextMemoryContent;
import io.haifa.agent.memory.api.DerivedTextType;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryCandidateDraft;
import io.haifa.agent.memory.api.MemoryCandidateStatus;
import io.haifa.agent.memory.api.MemoryConflictResolution;
import io.haifa.agent.memory.api.MemoryEvidenceRef;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryRef;
import io.haifa.agent.memory.api.MemoryRetentionPolicy;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryScopeType;
import io.haifa.agent.memory.api.MemorySecurityLabel;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemorySourceType;
import io.haifa.agent.memory.api.MemoryStatus;
import io.haifa.agent.memory.api.MemoryVisibility;
import io.haifa.agent.memory.api.TextMemoryContent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MemoryGovernanceTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final TenantRef TENANT = new TenantRef("tenant-a");
    private static final PrincipalRef OWNER = new PrincipalRef("user-a", "user");
    private static final MemoryActor REVIEWER = new MemoryActor(TENANT, OWNER, Set.of("memory:review", "memory:purge"));

    @Test
    void candidateAndMemoryAreSeparateAggregatesAndSensitiveDataRequiresReview() {
        Fixture fixture = fixture(new DefaultMemoryPolicy(true));
        var draft = draft(
                "sensitive-1",
                scope(MemoryScopeType.USER, OWNER.principalId()),
                "credential",
                "Access token: secret-value",
                source(MemorySourceType.EXPLICIT_USER_COMMAND, "message-1"),
                MemoryRetentionPolicy.RETAIN,
                true);
        fixture.verify(draft);

        assertThatThrownBy(() -> fixture.service.propose(
                        draft,
                        new MemoryActor(
                                new TenantRef("tenant-b"),
                                new PrincipalRef("user-b", "user"),
                                Set.of("memory:review"))))
                .isInstanceOf(SecurityException.class);

        var candidate = fixture.service.propose(draft, REVIEWER);

        assertThat(candidate.status()).isEqualTo(MemoryCandidateStatus.PENDING);
        assertThat(candidate.securityLabels()).contains(MemorySecurityLabel.CREDENTIAL);
        assertThat(candidate).isNotInstanceOf(Memory.class);
        assertThatThrownBy(() -> candidate
                        .reject("review rejected")
                        .approve(new MemoryRef(
                                new io.haifa.agent.memory.api.MemoryId("other"),
                                new io.haifa.agent.memory.api.MemoryVersion(1))))
                .isInstanceOf(IllegalStateException.class);

        var expiring = draft(
                "pending-expiry",
                scope(MemoryScopeType.RUN, "run-expiry"),
                "temporary",
                "temporary fact",
                source(MemorySourceType.MESSAGE, "message-expiry"),
                new MemoryRetentionPolicy("candidate-short", Optional.of(NOW), false),
                false);
        fixture.verify(expiring);
        var pending = fixture.service.propose(expiring, REVIEWER);
        fixture.service.evaluateExpiry(NOW);
        assertThat(fixture.store.find(pending.id()).orElseThrow().status()).isEqualTo(MemoryCandidateStatus.EXPIRED);
    }

    @Test
    void deduplicationApprovalAndConflictReplacementAreIdempotentAndVersioned() {
        Fixture fixture = fixture(new DefaultMemoryPolicy());
        MemoryScope scope = scope(MemoryScopeType.SESSION, "session-1");
        var firstDraft = draft(
                "request-1",
                scope,
                "preferred-language",
                "Java",
                source(MemorySourceType.MESSAGE, "message-1"),
                MemoryRetentionPolicy.RETAIN,
                false);
        fixture.verify(firstDraft);
        var firstCandidate = fixture.service.propose(firstDraft, REVIEWER);
        Memory first = fixture.service.approve(firstCandidate.id(), REVIEWER, "approve-1");
        assertThat(fixture.service.approve(firstCandidate.id(), REVIEWER, "approve-again"))
                .isEqualTo(first);

        var equivalent = draft(
                "request-2",
                scope,
                "preferred-language",
                " Java ",
                source(MemorySourceType.MESSAGE, "message-2"),
                MemoryRetentionPolicy.RETAIN,
                false);
        fixture.verify(equivalent);
        assertThat(fixture.service.propose(equivalent, REVIEWER).approvedMemory())
                .contains(new MemoryRef(first.id(), first.version()));

        var changed = draft(
                "request-3",
                scope,
                "preferred-language",
                "Kotlin",
                source(MemorySourceType.INTERACTION_RESPONSE, "interaction-1"),
                MemoryRetentionPolicy.RETAIN,
                false);
        fixture.verify(changed);
        var conflicting = fixture.service.propose(changed, REVIEWER);
        assertThatThrownBy(() -> fixture.service.approve(conflicting.id(), REVIEWER, "approve-conflict"))
                .isInstanceOf(IllegalStateException.class);
        var conflict = fixture.store.conflictFor(conflicting.id()).orElseThrow();

        Memory replacement = fixture.service.resolveConflict(
                conflict.id(), MemoryConflictResolution.REPLACE_WITH_CANDIDATE, REVIEWER, "resolve-1");

        assertThat(replacement.id()).isEqualTo(first.id());
        assertThat(replacement.version().value()).isEqualTo(2);
        assertThat(replacement.previousVersion()).contains(new MemoryRef(first.id(), first.version()));
        assertThat(fixture.store.find(first.id(), first.version()).orElseThrow().status())
                .isEqualTo(MemoryStatus.SUPERSEDED);
        assertThat(fixture.service.resolveConflict(
                        conflict.id(), MemoryConflictResolution.REPLACE_WITH_CANDIDATE, REVIEWER, "resolve-1"))
                .isEqualTo(replacement);
    }

    @Test
    void retrievalFiltersAuthorizationStatusAndExpiryBeforeDeterministicRankingAndBudget() {
        Fixture fixture = fixture(new DefaultMemoryPolicy());
        MemoryScope user = scope(MemoryScopeType.USER, OWNER.principalId());
        MemoryScope session = scope(MemoryScopeType.SESSION, "session-1");
        Memory java = fixture.approve(draft(
                "java",
                user,
                "language",
                "Java build Maven",
                source(MemorySourceType.MESSAGE, "m-java"),
                MemoryRetentionPolicy.RETAIN,
                false));
        fixture.approve(draft(
                "maven",
                session,
                "build-tool",
                "Maven wrapper",
                source(MemorySourceType.MESSAGE, "m-maven"),
                MemoryRetentionPolicy.RETAIN,
                false));
        var expiredDraft = draft(
                "expired",
                user,
                "old",
                "Java legacy",
                source(MemorySourceType.MESSAGE, "m-old"),
                new MemoryRetentionPolicy("short", Optional.of(NOW.minusSeconds(1)), false),
                false);
        Memory expired = fixture.approve(expiredDraft);
        MemoryScope otherTenant = new MemoryScope(
                new TenantRef("tenant-b"),
                new PrincipalRef("user-b", "user"),
                MemoryScopeType.USER,
                "user-b",
                MemoryVisibility.OWNER_ONLY,
                Set.of());
        fixture.approveAs(
                draft(
                        "other",
                        otherTenant,
                        "language",
                        "Java secret tenant",
                        source(MemorySourceType.MESSAGE, "m-other"),
                        MemoryRetentionPolicy.RETAIN,
                        false),
                new MemoryActor(otherTenant.tenant(), otherTenant.owner(), Set.of("memory:review")));
        MemoryScope otherOwner = new MemoryScope(
                TENANT,
                new PrincipalRef("user-b", "user"),
                MemoryScopeType.USER,
                "user-b",
                MemoryVisibility.OWNER_ONLY,
                Set.of());
        fixture.approveAs(
                draft(
                        "other-owner",
                        otherOwner,
                        "language",
                        "Java private owner",
                        source(MemorySourceType.MESSAGE, "m-owner"),
                        MemoryRetentionPolicy.RETAIN,
                        false),
                new MemoryActor(TENANT, otherOwner.owner(), Set.of("memory:review")));

        var retrieval = fixture.retriever.retrieve(new MemoryQuery(
                TENANT,
                OWNER,
                List.of(user, session),
                "java maven",
                Set.of(),
                Set.of(),
                10,
                java.content().orElseThrow().estimatedTokens(),
                NOW));

        assertThat(retrieval.results()).singleElement().satisfies(result -> {
            assertThat(result.memory().id()).isEqualTo(java.id());
            assertThat(result.selectionReason()).isEqualTo("keyword-match");
        });
        assertThat(retrieval.queryDigest()).startsWith("sha256:");
        fixture.service.evaluateExpiry(NOW);
        assertThat(fixture.store
                        .find(expired.id(), expired.version())
                        .orElseThrow()
                        .status())
                .isEqualTo(MemoryStatus.EXPIRED);
    }

    @Test
    void sourceInvalidationExpiryAndTwoStepPurgeStopRetrievalAndLeaveContentFreeTombstones() {
        Fixture fixture = fixture(new DefaultMemoryPolicy());
        MemoryScope scope = scope(MemoryScopeType.RUN, "run-1");
        MemorySourceRef source = source(MemorySourceType.MESSAGE, "source-message");
        Memory active = fixture.approve(
                draft("invalidate", scope, "fact", "Project uses Java", source, MemoryRetentionPolicy.RETAIN, false));
        assertThat(fixture.retriever.retrieve(query(scope, "Project Java")).results())
                .singleElement();

        assertThat(fixture.service.invalidateSource(source, "message redacted", REVIEWER))
                .containsExactly(new MemoryRef(active.id(), active.version()));
        assertThat(fixture.store
                        .find(active.id(), active.version())
                        .orElseThrow()
                        .status())
                .isEqualTo(MemoryStatus.INVALIDATED);
        assertThat(fixture.invalidated).contains(new MemoryRef(active.id(), active.version()));
        assertThat(fixture.retriever.retrieve(query(scope, "Project Java")).results())
                .isEmpty();

        fixture.service.requestPurge(scope, "user clear", REVIEWER);
        assertThat(fixture.store
                        .find(active.id(), active.version())
                        .orElseThrow()
                        .status())
                .isEqualTo(MemoryStatus.PURGE_PENDING);
        assertThat(fixture.retriever.retrieve(query(scope, "Project Java")).results())
                .isEmpty();
        var tombstones = fixture.service.executePurge(scope, "user clear", REVIEWER);
        assertThat(tombstones).singleElement().satisfies(tombstone -> assertThat(tombstone.toString())
                .doesNotContain("Project uses Java"));
        assertThat(fixture.store.find(active.id(), active.version()).orElseThrow())
                .satisfies(memory -> {
                    assertThat(memory.status()).isEqualTo(MemoryStatus.PURGED);
                    assertThat(memory.content()).isEmpty();
                });
        assertThat(fixture.retriever.retrieve(query(scope, "Project Java")).results())
                .isEmpty();
        assertThat(fixture.store.allCandidates()).isEmpty();
        assertThat(fixture.store.auditEvents())
                .allSatisfy(event -> assertThat(event.toString()).doesNotContain("Project uses Java"));
    }

    @Test
    void derivedOcrReferenceIsAllowedWhileRawBase64IsRejected() {
        var derived = new DerivedTextMemoryContent(
                new AssetRef("asset-derived", "text/plain", "page-1.ocr.txt"),
                DerivedTextType.OCR,
                "Invoice total is 42");
        assertThat(derived.derivedAsset().assetId()).isEqualTo("asset-derived");
        assertThatThrownBy(() -> new TextMemoryContent("data:image/png;base64," + "A".repeat(300)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Fixture fixture(DefaultMemoryPolicy policy) {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        InMemoryMemoryEvidenceVerifier verifier = new InMemoryMemoryEvidenceVerifier();
        AtomicInteger ids = new AtomicInteger();
        List<MemoryRef> invalidated = new ArrayList<>();
        var service = new DefaultMemoryService(
                store,
                store,
                policy,
                verifier,
                List.of((memory, reason) -> invalidated.add(memory)),
                store,
                () -> "memory-test-" + ids.incrementAndGet(),
                () -> NOW);
        return new Fixture(store, verifier, service, new DefaultMemoryRetriever(store, policy), invalidated);
    }

    private static MemoryCandidateDraft draft(
            String request,
            MemoryScope scope,
            String key,
            String text,
            MemorySourceRef source,
            MemoryRetentionPolicy retention,
            boolean auto) {
        return new MemoryCandidateDraft(
                request,
                scope,
                MemoryKind.FACT,
                key,
                new TextMemoryContent(text),
                List.of(source),
                List.of(new MemoryEvidenceRef(source, "sha256:" + request)),
                retention,
                auto);
    }

    private static MemorySourceRef source(MemorySourceType type, String id) {
        return new MemorySourceRef(type, id, Optional.empty());
    }

    private static MemoryScope scope(MemoryScopeType type, String target) {
        return new MemoryScope(TENANT, OWNER, type, target, MemoryVisibility.OWNER_ONLY, Set.of());
    }

    private static MemoryQuery query(MemoryScope scope, String text) {
        return new MemoryQuery(scope.tenant(), scope.owner(), List.of(scope), text, Set.of(), Set.of(), 10, 1_000, NOW);
    }

    private record Fixture(
            InMemoryMemoryStore store,
            InMemoryMemoryEvidenceVerifier verifier,
            DefaultMemoryService service,
            DefaultMemoryRetriever retriever,
            List<MemoryRef> invalidated) {
        void verify(MemoryCandidateDraft draft) {
            draft.evidence().forEach(evidence -> verifier.register(draft.scope(), evidence));
        }

        Memory approve(MemoryCandidateDraft draft) {
            return approveAs(draft, REVIEWER);
        }

        Memory approveAs(MemoryCandidateDraft draft, MemoryActor actor) {
            verify(draft);
            var candidate = service.propose(draft, actor);
            return service.approve(candidate.id(), actor, "approve:" + draft.requestKey());
        }
    }
}
