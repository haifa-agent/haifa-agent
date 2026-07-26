package io.haifa.agent.runtime.core.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.api.RuntimeErrorCode;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryInteractionPortTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final TenantRef TENANT = new TenantRef("tenant-1");
    private static final PrincipalRef OWNER = new PrincipalRef("owner-1", "user");
    private static final RuntimeCallerContext CALLER = new RuntimeCallerContext(TENANT, OWNER);

    @Test
    void enforcesOnePendingInteractionPerRun() {
        InMemoryInteractionPort port = new InMemoryInteractionPort();
        port.create(request("request-1", "run-1", false));

        assertThatThrownBy(() -> port.create(request("request-2", "run-1", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blocking pending");
    }

    @Test
    void appliesRevisionIdempotencyAndFirstResponseWins() {
        InMemoryInteractionPort port = new InMemoryInteractionPort();
        InteractionRequest request = request("request-1", "run-1", false);
        port.create(request);
        InteractionResponseSubmission submission =
                submission(request, "response-1", 0, InteractionAction.SUBMIT, "answer", "response-key");

        assertThat(port.respond(submission, CALLER, NOW.plusSeconds(1)).newlyRecorded())
                .isTrue();
        assertThat(port.respond(submission, CALLER, NOW.plusSeconds(2)).newlyRecorded())
                .isFalse();
        assertThat(port.record(request.id()).orElseThrow().state()).isEqualTo(InteractionState.RESPONDED);

        assertThatThrownBy(() -> port.respond(
                        submission(request, "response-2", 0, InteractionAction.SUBMIT, "different", "other-key"),
                        CALLER,
                        NOW.plusSeconds(2)))
                .isInstanceOf(RuntimeContractException.class)
                .extracting("code")
                .isEqualTo(RuntimeErrorCode.INTERACTION_REVISION_CONFLICT);

        port.markResolutionApplied(request.id());
        port.markResolutionApplied(request.id());
        assertThat(port.record(request.id()).orElseThrow().state()).isEqualTo(InteractionState.APPLIED);
    }

    @Test
    void rejectsChangedPayloadForTheSameIdempotencyKey() {
        InMemoryInteractionPort port = new InMemoryInteractionPort();
        InteractionRequest request = request("request-1", "run-1", false);
        port.create(request);
        port.respond(
                submission(request, "response-1", 0, InteractionAction.SUBMIT, "answer", "response-key"),
                CALLER,
                NOW.plusSeconds(1));

        assertThatThrownBy(() -> port.respond(
                        submission(request, "response-2", 0, InteractionAction.SUBMIT, "changed", "response-key"),
                        CALLER,
                        NOW.plusSeconds(2)))
                .isInstanceOf(RuntimeContractException.class)
                .extracting("code")
                .isEqualTo(RuntimeErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void expiresExactlyAtTheDeadlineAndRecordsAReasonCode() {
        InMemoryInteractionPort port = new InMemoryInteractionPort();
        InteractionRequest request = request("request-1", "run-1", false);
        port.create(request);

        assertThat(port.due(request.runId(), request.expiresAt().minusNanos(1), 10))
                .isEmpty();
        assertThat(port.due(request.runId(), request.expiresAt(), 10)).hasSize(1);
        assertThatThrownBy(() -> port.respond(
                        submission(request, "response-at-deadline", 0, InteractionAction.SUBMIT, "late", "late-key"),
                        CALLER,
                        request.expiresAt()))
                .isInstanceOf(RuntimeContractException.class)
                .extracting("code")
                .isEqualTo(RuntimeErrorCode.INTERACTION_EXPIRED);
        InteractionRecord expired = port.expire(request.id(), 0, request.expiresAt());

        assertThat(expired.state()).isEqualTo(InteractionState.EXPIRED);
        assertThat(expired.reasonCode()).contains("INTERACTION_EXPIRED");
    }

    @Test
    void appliesTheAuthoritativeKindActionMatrixAndKeepsUnknownKindsInert() {
        InMemoryInteractionPort confirmationPort = new InMemoryInteractionPort();
        InteractionRequest confirmation = new InteractionRequest(
                new InteractionRequestId("confirmation-1"),
                new AgentRunId("run-confirmation"),
                TENANT,
                OWNER,
                "confirmation",
                "Confirm the safe summary",
                false,
                NOW,
                NOW.plusSeconds(60));
        confirmationPort.create(confirmation);

        assertThatThrownBy(() -> confirmationPort.respond(
                        submission(
                                confirmation,
                                "response-invalid",
                                0,
                                InteractionAction.SUBMIT,
                                "not allowed",
                                "invalid-key"),
                        CALLER,
                        NOW.plusSeconds(1)))
                .isInstanceOf(RuntimeContractException.class)
                .extracting("code")
                .isEqualTo(RuntimeErrorCode.INTERACTION_ACTION_NOT_ALLOWED);
        assertThat(confirmationPort
                        .respond(
                                new InteractionResponseSubmission(
                                        new InteractionResponseId("response-confirm"),
                                        confirmation.id(),
                                        confirmation.runId(),
                                        0,
                                        InteractionAction.CONFIRM,
                                        List.of(),
                                        "confirm-key",
                                        NOW.plusSeconds(1)),
                                CALLER,
                                NOW.plusSeconds(1))
                        .newlyRecorded())
                .isTrue();

        InteractionRequest unknown = new InteractionRequest(
                new InteractionRequestId("unknown-1"),
                new AgentRunId("run-unknown"),
                TENANT,
                OWNER,
                "future-review",
                "Future safe prompt",
                false,
                NOW,
                NOW.plusSeconds(60));
        assertThat(InteractionSemantics.kind(unknown).known()).isFalse();
        assertThat(InteractionSemantics.allowedActions(InteractionSemantics.kind(unknown)))
                .isEmpty();
    }

    @Test
    void requiresReturnToAgentToBeExplicitAndNeverAllowsItForApproval() {
        InteractionRequest defaultInteraction = request("default-expiration", "run-default", false);
        assertThat(defaultInteraction.expirationOutcome()).isEqualTo(InteractionExpirationOutcome.FAIL_RUN);

        InteractionRequest resumable = new InteractionRequest(
                new InteractionRequestId("resumable-expiration"),
                new AgentRunId("run-resumable"),
                TENANT,
                OWNER,
                "clarification",
                "Safe public prompt",
                false,
                new GenericInteractionTarget("clarification"),
                NOW,
                NOW.plusSeconds(60),
                InteractionExpirationOutcome.RETURN_TO_AGENT,
                java.util.Optional.empty());
        assertThat(resumable.expirationOutcome()).isEqualTo(InteractionExpirationOutcome.RETURN_TO_AGENT);

        assertThatThrownBy(() -> new InteractionRequest(
                        new InteractionRequestId("approval-return"),
                        new AgentRunId("run-approval"),
                        TENANT,
                        OWNER,
                        "approval",
                        "Safe approval prompt",
                        true,
                        new GenericInteractionTarget("approval"),
                        NOW,
                        NOW.plusSeconds(60),
                        InteractionExpirationOutcome.RETURN_TO_AGENT,
                        java.util.Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not return");
    }

    private static InteractionRequest request(String requestId, String runId, boolean approval) {
        return new InteractionRequest(
                new InteractionRequestId(requestId),
                new AgentRunId(runId),
                TENANT,
                OWNER,
                approval ? "approval" : "clarification",
                "Safe public prompt",
                approval,
                new GenericInteractionTarget(approval ? "approval" : "clarification"),
                NOW,
                NOW.plusSeconds(60));
    }

    private static InteractionResponseSubmission submission(
            InteractionRequest request,
            String responseId,
            long revision,
            InteractionAction action,
            String text,
            String idempotencyKey) {
        return new InteractionResponseSubmission(
                new InteractionResponseId(responseId),
                request.id(),
                request.runId(),
                revision,
                action,
                List.of(new TextPart(text, "text/plain")),
                idempotencyKey,
                NOW.plusSeconds(1));
    }
}
