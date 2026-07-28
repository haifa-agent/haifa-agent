package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryAuditEvent;
import io.haifa.agent.memory.api.MemoryCandidate;
import io.haifa.agent.memory.api.MemoryCandidateId;
import io.haifa.agent.memory.api.MemoryCandidateQuery;
import io.haifa.agent.memory.api.MemoryCandidateStatus;
import io.haifa.agent.memory.api.MemoryEvidenceRef;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryOperationException;
import io.haifa.agent.memory.api.MemoryRecordQuery;
import io.haifa.agent.memory.api.MemoryRef;
import io.haifa.agent.memory.api.MemoryRetentionPolicy;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryScopeType;
import io.haifa.agent.memory.api.MemorySecurityLabel;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemorySourceType;
import io.haifa.agent.memory.api.MemoryStatus;
import io.haifa.agent.memory.api.MemoryVersion;
import io.haifa.agent.memory.api.MemoryVisibility;
import io.haifa.agent.memory.api.TextMemoryContent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMemoryStoreTest {
    @TempDir
    Path directory;

    @Test
    void persistsCandidateAndReplacementAcrossRestartAndAuthorizesBeforeReading() {
        MemoryScope scope = new MemoryScope(
                new TenantRef("tenant-a"),
                new PrincipalRef("user-a", "user"),
                MemoryScopeType.USER,
                "user-a",
                MemoryVisibility.OWNER_ONLY,
                Set.of());
        MemorySourceRef source = new MemorySourceRef(MemorySourceType.MESSAGE, "message-a", Optional.empty());
        MemoryEvidenceRef evidence = new MemoryEvidenceRef(source, "sha256:evidence");
        MemoryCandidate pending = new MemoryCandidate(
                new MemoryCandidateId("candidate-a"),
                "sha256:request",
                scope,
                MemoryKind.PREFERENCE,
                "language",
                new TextMemoryContent("Java"),
                List.of(source),
                List.of(evidence),
                MemoryCandidateStatus.PENDING,
                Set.of(MemorySecurityLabel.CONFIDENTIAL),
                "sha256:content",
                "policy-v1",
                MemoryRetentionPolicy.RETAIN,
                SqliteTestSupport.NOW,
                SqliteTestSupport.NOW,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        Memory first = memory(scope, 1, "Java", Optional.empty());
        Memory second = memory(scope, 2, "Kotlin", Optional.of(new MemoryRef(first.id(), first.version())));
        MemoryCandidate otherPending = new MemoryCandidate(
                new MemoryCandidateId("candidate-b"),
                "sha256:request-b",
                scope,
                MemoryKind.PREFERENCE,
                "timezone",
                new TextMemoryContent("UTC"),
                List.of(source),
                List.of(evidence),
                MemoryCandidateStatus.PENDING,
                Set.of(),
                "sha256:other-content",
                "policy-v1",
                MemoryRetentionPolicy.RETAIN,
                SqliteTestSupport.NOW,
                SqliteTestSupport.NOW,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            SqliteMemoryStore store = new SqliteMemoryStore(foundation.unitOfWork());
            store.save(pending);
            store.save(otherPending);
            assertThat(store.findEquivalentPending(scope, MemoryKind.PREFERENCE, pending.normalizedDigest()))
                    .contains(pending);
            assertThat(store.findEquivalentPending(scope, MemoryKind.PREFERENCE, otherPending.normalizedDigest()))
                    .contains(otherPending);
            store.save(first);
            foundation.unitOfWork().execute(() -> {
                store.save(first.invalidate(
                        "REPLACED", Optional.of(new MemoryRef(second.id(), second.version())), SqliteTestSupport.NOW));
                store.save(second);
                store.save(pending.approve(new MemoryRef(second.id(), second.version()), SqliteTestSupport.NOW));
                return null;
            });
        }

        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            SqliteMemoryStore store = new SqliteMemoryStore(foundation.unitOfWork());
            assertThat(store.find(first.id(), first.version()).orElseThrow().status())
                    .isEqualTo(MemoryStatus.INVALIDATED);
            assertThat(store.latest(first.id())
                            .orElseThrow()
                            .content()
                            .orElseThrow()
                            .boundedText())
                    .isEqualTo("Kotlin");
            assertThat(store.find(new MemoryCandidateId("candidate-a"))
                            .orElseThrow()
                            .revision())
                    .isEqualTo(1);
            assertThat(store.query(new MemoryCandidateQuery(
                                    scope,
                                    Set.of(MemoryCandidateStatus.APPROVED),
                                    Set.of(MemoryKind.PREFERENCE),
                                    Optional.empty(),
                                    Optional.empty(),
                                    1))
                            .items())
                    .containsExactly(
                            store.find(new MemoryCandidateId("candidate-a")).orElseThrow());
            assertThat(store.query(new MemoryRecordQuery(
                                    scope,
                                    Set.of(MemoryStatus.ACTIVE),
                                    Set.of(MemoryKind.PREFERENCE),
                                    Optional.empty(),
                                    Optional.empty(),
                                    1))
                            .items())
                    .containsExactly(second);
            assertThat(store.findAuthorized(
                            first.id(),
                            second.version(),
                            new MemoryActor(
                                    new TenantRef("tenant-b"),
                                    new PrincipalRef("user-b", "user"),
                                    Set.of("memory:read"))))
                    .isEmpty();
            assertThatThrownBy(store::allMemories)
                    .isInstanceOf(MemoryOperationException.class)
                    .hasMessage("MEMORY_DEFERRED_OPERATION");
        }

        assertThat(new String(readDatabase(), StandardCharsets.UTF_8)).contains("Kotlin");
    }

    @Test
    void rollsBackReplacementAndKeepsAuditFreeOfMemoryContent() {
        MemoryScope scope = new MemoryScope(
                new TenantRef("tenant-a"),
                new PrincipalRef("user-a", "user"),
                MemoryScopeType.USER,
                "user-a",
                MemoryVisibility.OWNER_ONLY,
                Set.of());
        Memory first = memory(scope, 1, "Java", Optional.empty());
        Memory second = memory(scope, 2, "Kotlin", Optional.of(new MemoryRef(first.id(), first.version())));

        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            SqliteMemoryStore store = new SqliteMemoryStore(
                    foundation.unitOfWork(),
                    SqliteTestSupport.configuration(directory).maximumPayloadBytes());
            store.save(first);
            store.record(new MemoryAuditEvent(
                    "memory.created",
                    Optional.empty(),
                    Optional.of(new MemoryRef(first.id(), first.version())),
                    scope,
                    "user-a",
                    Map.of("contentDigest", "sha256:java"),
                    SqliteTestSupport.NOW));

            assertThatThrownBy(() -> foundation.unitOfWork().execute(() -> {
                        store.save(first.invalidate(
                                "REPLACED",
                                Optional.of(new MemoryRef(second.id(), second.version())),
                                SqliteTestSupport.NOW));
                        throw new IllegalStateException("injected-before-new-version");
                    }))
                    .isInstanceOf(SqliteStoreException.class)
                    .hasRootCauseMessage("injected-before-new-version");

            assertThat(store.find(first.id(), first.version()).orElseThrow().status())
                    .isEqualTo(MemoryStatus.ACTIVE);
            assertThat(store.find(second.id(), second.version())).isEmpty();
            assertThat(auditPayloads(foundation)).singleElement().satisfies(payload -> assertThat(payload)
                    .contains("sha256:java")
                    .doesNotContain("Java")
                    .doesNotContain("Kotlin"));
        }
    }

    private byte[] readDatabase() {
        try {
            return Files.readAllBytes(SqliteTestSupport.configuration(directory).databasePath());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static List<String> auditPayloads(SqliteStoreFoundation foundation) {
        return foundation.unitOfWork().execute(() -> {
            try (PreparedStatement statement = foundation
                            .unitOfWork()
                            .currentConnection()
                            .prepareStatement("SELECT safe_attributes_json FROM memory_audit_event");
                    ResultSet rows = statement.executeQuery()) {
                List<String> values = new java.util.ArrayList<>();
                while (rows.next()) values.add(rows.getString(1));
                return values;
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static Memory memory(MemoryScope scope, long version, String text, Optional<MemoryRef> previous) {
        MemorySourceRef source = new MemorySourceRef(MemorySourceType.MESSAGE, "message-a", Optional.empty());
        return new Memory(
                new MemoryId("memory-a"),
                new MemoryVersion(version),
                scope,
                MemoryKind.PREFERENCE,
                "language",
                Optional.of(new TextMemoryContent(text)),
                List.of(source),
                List.of(new MemoryEvidenceRef(source, "sha256:evidence")),
                MemoryStatus.ACTIVE,
                Set.of(MemorySecurityLabel.CONFIDENTIAL),
                "sha256:" + text.toLowerCase(),
                previous,
                Optional.empty(),
                Optional.empty(),
                MemoryRetentionPolicy.RETAIN,
                SqliteTestSupport.NOW,
                SqliteTestSupport.NOW);
    }
}
