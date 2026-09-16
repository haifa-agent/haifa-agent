package io.haifa.agent.sdk.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryConversationStoreTest {
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("alice", "user");

    @Test
    void persistsMetadataAndEnforcesScopeRevisionAndTouchContract() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        AgentSessionId sessionId = new AgentSessionId("session-1");
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        ConversationRecord conversation =
                new ConversationRecord(sessionId, "Title", now, now, 0, ConversationStatus.ACTIVE);

        assertThat(store.create(conversation, TENANT, PRINCIPAL)).isEqualTo(conversation);
        assertThat(store.create(conversation, TENANT, PRINCIPAL)).isEqualTo(conversation);
        assertThatThrownBy(() -> store.create(conversation, TENANT, new PrincipalRef("bob", "user")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CONVERSATION_SCOPE_CONFLICT");
        assertThat(store.find(sessionId)).contains(conversation);
        assertThat(store.list(TENANT, PRINCIPAL, ConversationQuery.active(10)))
                .extracting("sessionId")
                .containsExactly(sessionId);
        assertThat(store.list(TENANT, new PrincipalRef("bob", "user"), ConversationQuery.active(10)))
                .isEmpty();

        ConversationRecord touched = store.touchLastActivity(sessionId, now.plusSeconds(1));
        assertThat(touched.revision()).isEqualTo(1);
        assertThat(touched.lastActivityAt()).isEqualTo(now.plusSeconds(1));

        assertThatThrownBy(() -> store.rename(sessionId, 0, "stale", now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CONVERSATION_REVISION_STALE");
        ConversationRecord renamed = store.rename(sessionId, touched.revision(), "Renamed", now.plusSeconds(2));
        assertThat(renamed.displayName()).isEqualTo("Renamed");
        assertThat(renamed.revision()).isEqualTo(2);

        ConversationRecord archived = store.changeStatus(
                sessionId,
                renamed.revision(),
                ConversationStatus.ACTIVE,
                ConversationStatus.ARCHIVED,
                now.plusSeconds(3));
        assertThat(archived.revision()).isEqualTo(3);
        assertThat(store.find(sessionId)).contains(archived);
    }
}
