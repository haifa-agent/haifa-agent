package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.sdk.conversation.ConversationCursor;
import io.haifa.agent.sdk.conversation.ConversationQuery;
import io.haifa.agent.sdk.conversation.ConversationRecord;
import io.haifa.agent.sdk.conversation.ConversationStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void persistsMetadataAndRecoversAcrossRestart(@TempDir Path directory) {
        AgentSessionId sessionId = new AgentSessionId("conversation-1");
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            provision(foundation, sessionId);
            SqliteConversationStore store = new SqliteConversationStore(foundation.unitOfWork());

            ConversationRecord created = store.create(conversation(sessionId), TENANT, PRINCIPAL);
            assertThat(created.revision()).isZero();
            assertThat(store.create(conversation(sessionId), TENANT, PRINCIPAL)).isEqualTo(created);

            ConversationRecord touched = store.touchLastActivity(sessionId, NOW.plusSeconds(1));
            assertThat(touched.revision()).isEqualTo(1);
            assertThat(touched.lastActivityAt()).isEqualTo(NOW.plusSeconds(1));
            assertThat(store.list(TENANT, PRINCIPAL, ConversationQuery.active(10)))
                    .extracting("sessionId")
                    .containsExactly(sessionId);
        }

        try (SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory)) {
            SqliteConversationStore store = new SqliteConversationStore(reopened.unitOfWork());
            ConversationRecord recovered = store.find(sessionId).orElseThrow();
            assertThat(recovered.sessionId()).isEqualTo(sessionId);
            assertThat(recovered.displayName()).isEqualTo("First");
            assertThat(recovered.revision()).isEqualTo(1);
            assertThat(recovered.lastActivityAt()).isEqualTo(NOW.plusSeconds(1));
        }
    }

    @Test
    void enforcesScopeRevisionAndStableCursor(@TempDir Path directory) throws Exception {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            SqliteConversationStore store = new SqliteConversationStore(foundation.unitOfWork());
            AgentSessionId first = new AgentSessionId("conversation-a");
            AgentSessionId second = new AgentSessionId("conversation-b");
            provision(foundation, first);
            provision(foundation, second);
            store.create(conversation(first), TENANT, PRINCIPAL);
            store.create(
                    new ConversationRecord(second, "Second", NOW, NOW.plusSeconds(1), 0, ConversationStatus.ACTIVE),
                    TENANT,
                    PRINCIPAL);
            assertThatThrownBy(() -> store.create(conversation(first), TENANT, new PrincipalRef("bob", "user")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("CONVERSATION_SCOPE_CONFLICT");

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

            store.touchLastActivity(first, NOW);
            assertThatThrownBy(() -> store.rename(first, 0, "stale", NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("CONVERSATION_REVISION_STALE");

            AgentSessionId competing = new AgentSessionId("conversation-revision-race");
            provision(foundation, competing);
            store.create(conversation(competing), TENANT, PRINCIPAL);
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
        return new ConversationRecord(sessionId, "First", NOW, NOW, 0, ConversationStatus.ACTIVE);
    }
}
