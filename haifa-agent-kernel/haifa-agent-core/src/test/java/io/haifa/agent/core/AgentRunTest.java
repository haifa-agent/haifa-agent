package io.haifa.agent.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.SessionId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentRunTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void followsTheRunLifecycle() {
        AgentRun created = AgentRun.create(SessionId.create(), CREATED_AT);
        AgentRun running = created.transitionTo(AgentRunStatus.RUNNING, CREATED_AT.plusSeconds(1));
        AgentRun completed = running.transitionTo(AgentRunStatus.COMPLETED, CREATED_AT.plusSeconds(2));

        assertThat(created.status()).isEqualTo(AgentRunStatus.NEW);
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.id()).isEqualTo(created.id());
        assertThat(completed.status().isTerminal()).isTrue();
    }

    @Test
    void rejectsInvalidTransitions() {
        AgentRun created = AgentRun.create(SessionId.create(), CREATED_AT);

        assertThatThrownBy(() -> created.transitionTo(AgentRunStatus.COMPLETED, CREATED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEW")
                .hasMessageContaining("COMPLETED");
    }
}
