package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.sdk.conversation.ConversationCommandBinding;
import io.haifa.agent.sdk.conversation.ConversationCursor;
import io.haifa.agent.sdk.conversation.ConversationQuery;
import io.haifa.agent.sdk.conversation.ConversationRecord;
import io.haifa.agent.sdk.conversation.ConversationStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteConversationStoreTest {
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("alice", "user");
    private static final Instant NOW = SqliteTestSupport.NOW;

    @Test
    void persistsContractAndRecoversAcrossRestart(@TempDir Path directory) {
        AgentSessionId sessionId = new AgentSessionId("conversation-1");
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            provision(foundation, sessionId);
            SqliteConversationStore store = new SqliteConversationStore(foundation.unitOfWork());
            ConversationCommandBinding command = command(sessionId, "digest-a");

            assertThat(store.reserveCommand(command)).isEqualTo(command);
            assertThat(store.reserveCommand(command)).isEqualTo(command);
            assertThatThrownBy(() -> store.reserveCommand(command(sessionId, "digest-b")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("CONVERSATION_IDEMPOTENCY_CONFLICT");

            ConversationRecord created = store.create(conversation(sessionId));
            ConversationRecord reserved =
                    store.reserveActive(sessionId, created.revision(), "dispatch-1", NOW.plusSeconds(1));
            ConversationRecord active =
                    store.activateRun(sessionId, "dispatch-1", new AgentRunId("run-1"), 3, NOW.plusSeconds(2));
            ConversationCommandBinding completed =
                    store.completeCommand("dispatch-1", Optional.of(new AgentRunId("run-1")), active.revision());

            assertThat(reserved.activeDispatchKey()).contains("dispatch-1");
            assertThat(active.activeRunId()).contains(new AgentRunId("run-1"));
            assertThat(completed.completed()).isTrue();
            assertThat(store.list(TENANT, PRINCIPAL, ConversationQuery.active(10)))
                    .extracting("sessionId")
                    .containsExactly(sessionId);
        }

        try (SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory)) {
            SqliteConversationStore store = new SqliteConversationStore(reopened.unitOfWork());
            ConversationRecord recovered = store.find(sessionId).orElseThrow();

            assertThat(recovered.activeRunId()).contains(new AgentRunId("run-1"));
            assertThat(recovered.activeRunVersion()).hasValue(3);
            assertThat(store.reserveCommand(command(sessionId, "digest-a")).completed())
                    .isTrue();
        }
    }

    @Test
    void enforcesRevisionSingleActiveRunAndStableCursor(@TempDir Path directory) throws Exception {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            SqliteConversationStore store = new SqliteConversationStore(foundation.unitOfWork());
            AgentSessionId first = new AgentSessionId("conversation-a");
            AgentSessionId second = new AgentSessionId("conversation-b");
            provision(foundation, first);
            provision(foundation, second);
            store.create(conversation(first));
            store.create(new ConversationRecord(
                    second,
                    TENANT,
                    PRINCIPAL,
                    "Second",
                    ConversationStatus.ACTIVE,
                    Optional.empty(),
                    OptionalLong.empty(),
                    Optional.empty(),
                    NOW,
                    NOW.plusSeconds(1),
                    0));

            var firstPage = store.list(TENANT, PRINCIPAL, ConversationQuery.active(1));
            assertThat(firstPage).hasSize(2);
            ConversationRecord pageBoundary = firstPage.getFirst();
            var secondPage = store.list(
                    TENANT,
                    PRINCIPAL,
                    new ConversationQuery(
                            Optional.empty(),
                            Set.of(ConversationStatus.ACTIVE),
                            Optional.of(
                                    new ConversationCursor(pageBoundary.lastActivityAt(), pageBoundary.sessionId())),
                            1));
            assertThat(secondPage).extracting("sessionId").containsExactly(first);
            assertThat(store.list(
                            TENANT,
                            PRINCIPAL,
                            new ConversationQuery(
                                    Optional.of("%"), Set.of(ConversationStatus.ACTIVE), Optional.empty(), 10)))
                    .isEmpty();

            try (var executor = Executors.newFixedThreadPool(2)) {
                var start = new java.util.concurrent.CountDownLatch(1);
                Callable<Boolean> reserve = () -> {
                    start.await();
                    try {
                        store.reserveActive(
                                first, 0, "dispatch-" + Thread.currentThread().threadId(), NOW);
                        return true;
                    } catch (IllegalStateException expected) {
                        return false;
                    }
                };
                var attempts = List.of(executor.submit(reserve), executor.submit(reserve));
                start.countDown();
                assertThat(attempts.stream().map(future -> {
                            try {
                                return future.get();
                            } catch (Exception exception) {
                                throw new AssertionError(exception);
                            }
                        }))
                        .containsExactlyInAnyOrder(true, false);
            }
            assertThatThrownBy(() -> store.rename(first, 0, "stale", NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("CONVERSATION_REVISION_STALE");

            AgentSessionId competing = new AgentSessionId("conversation-revision-race");
            provision(foundation, competing);
            store.create(conversation(competing));
            try (var executor = Executors.newFixedThreadPool(2)) {
                var start = new java.util.concurrent.CountDownLatch(1);
                Callable<Boolean> rename = () -> {
                    start.await();
                    try {
                        store.rename(competing, 0, "renamed", NOW.plusSeconds(2));
                        return true;
                    } catch (IllegalStateException expected) {
                        return false;
                    }
                };
                Callable<Boolean> archive = () -> {
                    start.await();
                    try {
                        store.changeStatus(
                                competing,
                                0,
                                ConversationStatus.ACTIVE,
                                ConversationStatus.ARCHIVED,
                                NOW.plusSeconds(2));
                        return true;
                    } catch (IllegalStateException expected) {
                        return false;
                    }
                };
                var attempts = List.of(executor.submit(rename), executor.submit(archive));
                start.countDown();
                assertThat(attempts.stream().map(future -> {
                            try {
                                return future.get();
                            } catch (Exception exception) {
                                throw new AssertionError(exception);
                            }
                        }))
                        .containsExactlyInAnyOrder(true, false);
            }
        }
    }

    private static void provision(SqliteStoreFoundation foundation, AgentSessionId sessionId) {
        foundation
                .agentSessions()
                .insert(AgentSession.open(sessionId, TENANT, PRINCIPAL, null, SessionScope.USER, NOW, Map.of()));
    }

    private static ConversationRecord conversation(AgentSessionId sessionId) {
        return new ConversationRecord(
                sessionId,
                TENANT,
                PRINCIPAL,
                "First",
                ConversationStatus.ACTIVE,
                Optional.empty(),
                OptionalLong.empty(),
                Optional.empty(),
                NOW,
                NOW,
                0);
    }

    private static ConversationCommandBinding command(AgentSessionId sessionId, String requestDigest) {
        return new ConversationCommandBinding(
                "sha256:caller",
                "submit",
                "sha256:key",
                requestDigest,
                "dispatch-1",
                sessionId,
                Optional.empty(),
                false,
                OptionalLong.empty(),
                NOW);
    }
}
