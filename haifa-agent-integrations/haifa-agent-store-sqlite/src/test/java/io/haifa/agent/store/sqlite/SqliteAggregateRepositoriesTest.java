package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.InteractionRequestRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunSpec;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteAggregateRepositoriesTest {

    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

    @Test
    void runAndAttemptRoundTripAndEnforceVersionsAndActiveAttempt(@TempDir java.nio.file.Path directory) {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        prepareParents(foundation);
        AgentRun run = AgentRun.createRoot(new AgentRunId("run"), runSpec(), NOW);
        foundation.runs().insert(run);
        run.start(NOW.plusSeconds(1));
        run.waitForApproval(new InteractionRequestRef("approval", "tool"), NOW.plusSeconds(2));
        foundation.runs().save(run, 0);

        assertThat(foundation.runs().find(run.id()).orElseThrow().persistenceSnapshot())
                .isEqualTo(run.persistenceSnapshot());
        assertThatThrownBy(() -> foundation.runs().save(run, 0)).isInstanceOf(OptimisticLockException.class);

        AgentRunExecutionAttempt attempt =
                new AgentRunExecutionAttempt(new ExecutionAttemptId("attempt-1"), run.id(), 1, NOW, Optional.empty());
        foundation.attempts().insert(attempt);
        assertThat(foundation.attempts().activeFor(run.id())).isPresent();
        assertThatThrownBy(() -> foundation
                        .attempts()
                        .insert(new AgentRunExecutionAttempt(
                                new ExecutionAttemptId("attempt-2"), run.id(), 2, NOW, Optional.empty())))
                .isInstanceOf(IllegalStateException.class);

        attempt.start("worker", NOW.plusSeconds(1));
        foundation.attempts().save(attempt, 0);
        assertThat(foundation.attempts().find(attempt.attemptId()).orElseThrow().persistenceSnapshot())
                .isEqualTo(attempt.persistenceSnapshot());
        assertThat(foundation.attempts().attemptsFor(run.id())).hasSize(1);
    }

    private static void prepareParents(SqliteStoreFoundation foundation) {
        AgentSession session = AgentSession.open(
                new AgentSessionId("session"),
                new TenantRef("tenant"),
                new PrincipalRef("principal", "user"),
                null,
                SessionScope.USER,
                NOW,
                Map.of());
        foundation.agentSessions().insert(session);
        foundation.unitOfWork().execute(() -> {
            try (PreparedStatement statement = foundation
                    .unitOfWork()
                    .currentConnection()
                    .prepareStatement(
                            """
                            INSERT INTO configuration_snapshot (
                                configuration_ref, schema_version, definition_id, definition_version,
                                profile_id, profile_version, run_type, content_schema_version,
                                content_payload, content_hash, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)) {
                statement.setString(1, "config");
                statement.setString(2, "1");
                statement.setString(3, "agent");
                statement.setString(4, "1.0.0");
                statement.setString(5, "profile");
                statement.setString(6, "1");
                statement.setString(7, "chat");
                statement.setString(8, "1");
                statement.setBytes(9, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                statement.setString(10, "sha256:config");
                statement.setLong(11, NOW.toEpochMilli());
                statement.executeUpdate();
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
            return null;
        });
    }

    private static AgentRunSpec runSpec() {
        return new AgentRunSpec(
                new AgentSessionId("session"),
                null,
                new TenantRef("tenant"),
                new PrincipalRef("principal", "user"),
                new AgentDefinitionId("agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "profile",
                "1",
                AgentRunType.CHAT,
                "objective",
                new AgentRunBudget(100, 100, 100, 10, 10, 2, "USD", 100),
                new AgentRunLimits(10, 2, 1, 60_000, 10_000),
                new RunConfigurationSnapshotRef("config", "sha256:config"));
    }
}
