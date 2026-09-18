package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteAgentSessionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

    @Test
    void roundTripsEverySessionFieldAndUsesExpectedVersion(@TempDir java.nio.file.Path directory) {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        SqliteAgentSessionRepository repository = foundation.agentSessions();
        AgentSession session = AgentSession.open(
                new AgentSessionId("session"),
                new TenantRef("tenant"),
                new PrincipalRef("owner", "user"),
                new ProjectRef("project"),
                SessionScope.PROJECT,
                NOW,
                Map.of("nested", List.of("value"), "enabled", true));

        repository.insert(session);
        session.archive(NOW.plusSeconds(1));
        repository.save(session, 0);

        assertThat(repository.find(session.id()).orElseThrow().persistenceSnapshot())
                .isEqualTo(session.persistenceSnapshot());
        assertThatThrownBy(() -> repository.save(session, 0)).isInstanceOf(OptimisticLockException.class);
    }

    @Test
    void rejectsDuplicatePrimaryKey(@TempDir java.nio.file.Path directory) {
        SqliteAgentSessionRepository repository =
                SqliteTestSupport.foundation(directory).agentSessions();
        AgentSession session = AgentSession.open(
                new AgentSessionId("session"),
                new TenantRef("tenant"),
                new PrincipalRef("owner", "user"),
                null,
                SessionScope.USER,
                NOW,
                Map.of());

        repository.insert(session);

        assertThatThrownBy(() -> repository.insert(session)).isInstanceOf(IllegalStateException.class);
    }
}
