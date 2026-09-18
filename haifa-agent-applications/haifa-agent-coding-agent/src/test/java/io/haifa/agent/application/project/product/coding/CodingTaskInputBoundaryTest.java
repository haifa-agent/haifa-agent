package io.haifa.agent.application.project.product.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodingTaskInputBoundaryTest {
    private static final AgentSessionId SESSION_ID = new AgentSessionId("session-input-boundary");
    private static final AgentRunId RUN_ID = new AgentRunId("run-input-boundary");
    private static final ProjectId PROJECT_ID = new ProjectId("project-input-boundary");

    @Test
    void acceptsProductMessagesAtDocumentedBoundary() {
        String message = "x".repeat(65_536);

        assertThat(commandBinding(message).message()).hasSize(65_536);
        assertThat(followUp(message).message()).hasSize(65_536);
        assertThat(restoredMessage(message).message()).hasSize(65_536);
    }

    @Test
    void rejectsProductMessagesBeyondDocumentedBoundary() {
        String message = "x".repeat(65_537);

        assertThatThrownBy(() -> commandBinding(message)).hasMessageContaining("message");
        assertThatThrownBy(() -> followUp(message)).hasMessageContaining("message");
        assertThatThrownBy(() -> restoredMessage(message)).hasMessageContaining("message");
    }

    private static CodingCommandBinding commandBinding(String message) {
        return new CodingCommandBinding(
                "caller-scope",
                "start",
                "idempotency",
                "request",
                "dispatch",
                SESSION_ID,
                PROJECT_ID,
                message,
                List.of(),
                Optional.empty(),
                Instant.EPOCH);
    }

    private static CodingFollowUp followUp(String message) {
        return new CodingFollowUp(
                "follow-up",
                SESSION_ID,
                RUN_ID,
                message,
                List.of(),
                "idempotency",
                "request",
                "dispatch",
                CodingFollowUpStatus.PENDING,
                1,
                Optional.empty(),
                Instant.EPOCH,
                Instant.EPOCH,
                0);
    }

    private static CodingRestoredMessage restoredMessage(String message) {
        return new CodingRestoredMessage("follow-up", SESSION_ID, message, List.of(), 0);
    }
}
