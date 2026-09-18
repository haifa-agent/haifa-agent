package io.haifa.agent.runtime.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import io.haifa.agent.runtime.core.execution.ExecutionOwnershipPort;
import io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort;
import io.haifa.agent.runtime.core.tool.InMemoryToolExecutionJournal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuntimePersistencePortsTest {

    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

    @Test
    void rejectsEveryMissingRequiredPort() {
        RuntimePersistencePorts complete = RuntimePersistencePorts.inMemory();

        for (int missing = 0; missing < RuntimePersistencePorts.class.getRecordComponents().length; missing++) {
            int index = missing;
            assertThatThrownBy(() -> copyWithMissing(complete, index)).isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void inMemoryCombinationPreservesExplicitJournalAndInteractionPorts() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        InMemoryToolExecutionJournal journal = new InMemoryToolExecutionJournal();
        InMemoryInteractionPort interactions = new InMemoryInteractionPort();

        RuntimePersistencePorts ports = RuntimePersistencePorts.inMemory(store, journal, interactions);

        assertThat(ports.toolJournal()).isSameAs(journal);
        assertThat(ports.interactions()).isSameAs(interactions);
        assertThat(ports.runInputs()).isNotNull();
        assertThat(ports.runs()).isSameAs(store);
        assertThat(ports.messageRedactions()).isSameAs(store);
    }

    @Test
    void inMemorySessionRepositoryUsesExpectedVersionOptimisticLocking() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentSession session = AgentSession.open(
                new AgentSessionId("session"),
                new TenantRef("tenant"),
                new PrincipalRef("owner", "user"),
                null,
                SessionScope.USER,
                NOW,
                Map.of());
        store.insert(session);
        session.archive(NOW.plusSeconds(1));
        store.save(session, 0);

        AgentSession stale = AgentSession.reconstitute(session.persistenceSnapshot());
        stale.close(NOW.plusSeconds(2));

        assertThatThrownBy(() -> store.save(stale, 0)).isInstanceOf(OptimisticLockException.class);
        assertThat(store.find(session.id())).contains(session);
    }

    @Test
    void localOwnershipRequiresTheCurrentProcessInstanceId() {
        AgentRunExecutionAttempt current = attempt("current", "process-current");
        AgentRunExecutionAttempt old = attempt("old", "process-old");
        ExecutionOwnershipPort ownership = ExecutionOwnershipPort.local("process-current");

        assertThat(ownership.stillOwned(current)).isTrue();
        assertThat(ownership.stillOwned(old)).isFalse();
        assertThat(ownership.stillOwned(new AgentRunExecutionAttempt(
                        new ExecutionAttemptId("queued"), new AgentRunId("run"), 2, NOW, Optional.empty())))
                .isFalse();
    }

    private static AgentRunExecutionAttempt attempt(String id, String workerId) {
        AgentRunExecutionAttempt attempt = new AgentRunExecutionAttempt(
                new ExecutionAttemptId(id), new AgentRunId("run"), 1, NOW, Optional.empty());
        attempt.start(workerId, NOW.plusSeconds(1));
        return attempt;
    }

    private static RuntimePersistencePorts copyWithMissing(RuntimePersistencePorts source, int missing) {
        return new RuntimePersistencePorts(
                missing == 0 ? null : source.sessions(),
                missing == 1 ? null : source.runs(),
                missing == 2 ? null : source.attempts(),
                missing == 3 ? null : source.checkpoints(),
                missing == 4 ? null : source.state(),
                missing == 5 ? null : source.events(),
                missing == 6 ? null : source.outbox(),
                missing == 7 ? null : source.idempotency(),
                missing == 8 ? null : source.unitOfWork(),
                missing == 9 ? null : source.toolJournal(),
                missing == 10 ? null : source.interactions(),
                missing == 11 ? null : source.runInputs(),
                missing == 12 ? null : source.conversationSummaries(),
                missing == 13 ? null : source.toolResultAssets(),
                missing == 14 ? null : source.messageRedactions());
    }
}
