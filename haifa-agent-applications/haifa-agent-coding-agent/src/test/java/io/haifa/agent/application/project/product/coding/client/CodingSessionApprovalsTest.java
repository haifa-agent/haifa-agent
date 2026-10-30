package io.haifa.agent.application.project.product.coding.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.tool.ProjectToolCatalog;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.api.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CodingSessionApprovalsTest {
    @Test
    void contentOnlyTruncationDoesNotInvalidateACompleteWriteTarget() {
        var f = new Fixture();
        f.states.put(f.pending.requestId(), InteractionState.APPLIED);
        f.approvals.approve(f.pending, submission(f.pending));
        for (String tool : List.of("file_create", "file_write", "file_diff")) {
            f.truncated = true;
            f.completeFields = java.util.Set.of("path");
            f.next("session-1", "run-" + tool, tool, Map.of("path", "/workspace/large.txt", "content", "preview"));
            assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                    .isEmpty();
        }
        f.completeFields = java.util.Set.of();
        f.next("session-1", "run-incomplete", "file_write", Map.of("path", "preview", "content", "preview"));
        assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .hasValueSatisfying(prompt -> {
                    assertThat(prompt.allowForSession()).isFalse();
                    assertThat(prompt.sessionApprovalUnavailableReason()).isPresent();
                });
        f.next("session-1", "run-patch", "file_patch", Map.of("patch", "preview"));
        assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .hasValueSatisfying(
                        prompt -> assertThat(prompt.allowForSession()).isFalse());
    }

    @Test
    void redactedContentDoesNotHideAnUncertifiedTarget() {
        var f = new Fixture();
        f.completeFields = Set.of("path");
        f.next(
                "session-1",
                "content-redacted",
                "file_write",
                Map.of("path", "/workspace/large.txt", "content", "[REDACTED]"));
        assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .hasValueSatisfying(
                        prompt -> assertThat(prompt.allowForSession()).isTrue());
        f.completeFields = Set.of("content");
        f.next("session-1", "path-redacted", "file_write", Map.of("path", "[REDACTED]", "content", "body"));
        assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .hasValueSatisfying(
                        prompt -> assertThat(prompt.allowForSession()).isFalse());
    }

    @Test
    void rejectedOrMismatchedAutomaticReceiptsRestoreExplicitApprovalAndClearConsent() {
        for (boolean mismatch : List.of(false, true)) {
            var f = new Fixture();
            f.states.put(f.pending.requestId(), InteractionState.APPLIED);
            f.approvals.approve(f.pending, submission(f.pending));
            f.next("session-1", "next", "file_write", Map.of());
            f.states.put(f.pending.requestId(), mismatch ? InteractionState.APPLIED : InteractionState.INVALIDATED);
            f.mismatchedReceipt = mismatch;
            assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                    .isPresent();
            int attempts = f.submissions.size();
            f.states.put(f.pending.requestId(), InteractionState.PENDING);
            f.mismatchedReceipt = false;
            assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                    .isPresent();
            assertThat(f.submissions).hasSize(attempts);
        }
    }

    @Test
    void automaticResponseOrSubmissionFailureFallsBackToExplicitApproval() {
        for (boolean factoryFailure : List.of(false, true)) {
            var f = new Fixture();
            f.states.put(f.pending.requestId(), InteractionState.APPLIED);
            f.approvals.approve(f.pending, submission(f.pending));
            f.next("session-1", "run-next", "file_write", Map.of());
            f.failSubmission = !factoryFailure;
            assertThat(f.approvals.prepare(f.pending.runId(), pending -> {
                        if (factoryFailure)
                            throw new RuntimeContractException(RuntimeApiErrorCode.INTERACTION_NOT_FOUND, "Stale");
                        return submission(pending);
                    }))
                    .hasValueSatisfying(
                            prompt -> assertThat(prompt.allowForSession()).isTrue());
            f.failSubmission = false;
            assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                    .isPresent();
        }
    }

    @Test
    void pendingConsentOnlyActivatesAfterTheExactSubmissionWasApplied() {
        var f = new Fixture();
        var first = f.pending;
        f.approvals.approve(first, submission(first));
        f.next("session-1", "run-2", "file_patch", Map.of());
        assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .isPresent();
        assertThat(f.submissions).allMatch(value -> value.requestId().equals(first.requestId()));

        f.states.put(first.requestId(), InteractionState.APPLIED);
        assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .isEmpty();
        assertThat(f.submissions.getLast().requestId()).isEqualTo(f.pending.requestId());
        assertThat(f.submissions.getLast().action()).isEqualTo(InteractionAction.APPROVE);
    }

    @Test
    void consentIsLimitedToTheSessionAndClientProcessAndCanBeReset() {
        var f = new Fixture();
        var first = f.pending;
        f.states.put(first.requestId(), InteractionState.APPLIED);
        f.approvals.approve(first, submission(first));
        f.next("session-2", "run-2", "execution_run", Map.of());
        assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .isPresent();
        f.next("session-1", "run-3", "execution_run", Map.of());
        assertThat(new CodingSessionApprovals(f.runtime)
                        .prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .isPresent();
        assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .isEmpty();
        f.approvals.reset(new AgentSessionId("session-1"));
        f.next("session-1", "run-4", "file_write", Map.of());
        assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .isPresent();
    }

    @Test
    void neverAutoApprovesWorkspaceTrustPermissionElevationOrUnknownTools() {
        var f = new Fixture();
        var first = f.pending;
        f.states.put(first.requestId(), InteractionState.APPLIED);
        f.approvals.approve(first, submission(first));
        int submissions = f.submissions.size();
        for (String name : List.of("workspace_attach", "unknown_tool", "execution_run")) {
            f.next("session-1", "run-2", name, Map.of("operationFamily", "PERMISSION_REQUEST"));
            assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                    .hasValueSatisfying(
                            prompt -> assertThat(prompt.allowForSession()).isFalse());
            assertThatThrownBy(() -> f.approvals.approve(f.pending, submission(f.pending)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(f.submissions).hasSize(submissions);
    }

    @Test
    void sessionConsentFollowsTheBuiltInToolCatalog() {
        assertThat(CodingSessionApprovals.ordinaryToolNames())
                .containsAll(ProjectToolCatalog.BUILT_IN_TOOL_NAMES.stream()
                        .filter(name -> !name.equals(ProjectToolCatalog.WORKSPACE_ATTACH))
                        .toList())
                .doesNotContain(ProjectToolCatalog.WORKSPACE_ATTACH)
                .contains("web_search", "web_fetch", "skill_load", "skill_resource_read");

        var f = new Fixture();
        f.states.put(f.pending.requestId(), InteractionState.APPLIED);
        f.approvals.approve(f.pending, submission(f.pending));
        for (String name : List.of("file_diff", "file_search", "file_patch")) {
            f.next("session-1", "run-" + name, name, Map.of());
            assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                    .isEmpty();
        }
    }

    @Test
    void duplicatePreparationReusesTheSameAutomaticSubmission() {
        var f = new Fixture();
        f.states.put(f.pending.requestId(), InteractionState.APPLIED);
        f.approvals.approve(f.pending, submission(f.pending));
        f.next("session-1", "run-2", "execution_run", Map.of());
        var generated = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Function<InteractionView, InteractionResponseSubmission> factory = pending -> {
            generated.incrementAndGet();
            return submission(pending);
        };
        f.approvals.prepare(f.pending.runId(), factory);
        f.approvals.prepare(f.pending.runId(), factory);
        assertThat(generated).hasValue(1);
        assertThat(f.submissions.getLast()).isEqualTo(f.submissions.get(f.submissions.size() - 2));
    }

    @Test
    void resetBeforeApplicationDoesNotRestoreConsentFromTheLateReceipt() {
        var f = new Fixture();
        var first = f.pending;
        f.approvals.approve(first, submission(first));
        f.approvals.reset(first.sessionId());
        f.states.put(first.requestId(), InteractionState.APPLIED);
        f.next("session-1", "run-2", "file_write", Map.of());
        assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .isPresent();
        assertThat(f.submissions).hasSize(1);
    }

    @Test
    void nonApprovalAndCredentialInteractionsRemainExplicit() {
        var f = new Fixture();
        f.states.put(f.pending.requestId(), InteractionState.APPLIED);
        f.approvals.approve(f.pending, submission(f.pending));
        f.kind = InteractionKind.INPUT_REQUIRED;
        f.next("session-1", "run-2", "execution_run", Map.of());
        assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .hasValueSatisfying(
                        prompt -> assertThat(prompt.allowForSession()).isFalse());
        assertThat(f.submissions).hasSize(1);
    }

    @Test
    void failedSubmissionCannotEnableSessionConsent() {
        var f = new Fixture();
        f.failSubmission = true;
        assertThatThrownBy(() -> f.approvals.approve(f.pending, submission(f.pending)))
                .isInstanceOf(IllegalStateException.class);
        f.failSubmission = false;
        f.next("session-1", "run-2", "file_write", Map.of());
        assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .isPresent();
    }

    @Test
    void unavailableInitialReceiptClearsConsentAndLeavesTheNextApprovalExplicit() {
        var f = new Fixture();
        var first = f.pending;
        f.approvals.approve(first, submission(first));
        f.next("session-1", "run-2", "file_write", Map.of());
        f.unavailableReceipt = true;
        assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .hasValueSatisfying(
                        prompt -> assertThat(prompt.allowForSession()).isTrue());
        f.unavailableReceipt = false;
        f.states.put(first.requestId(), InteractionState.APPLIED);
        assertThat(f.approvals.prepare(f.pending.runId(), CodingSessionApprovalsTest::submission))
                .isPresent();
        assertThat(f.submissions).hasSize(1);
    }

    private static InteractionResponseSubmission submission(InteractionView view) {
        return new InteractionResponseSubmission(
                new InteractionResponseId("response-" + view.requestId().value()),
                view.requestId(),
                view.runId(),
                view.revision(),
                InteractionAction.APPROVE,
                List.of(),
                "key-" + view.requestId().value(),
                Instant.EPOCH);
    }

    private static final class Fixture {
        InteractionView pending;
        ToolCallView call;
        int sequence;
        InteractionKind kind = InteractionKind.APPROVAL;
        boolean failSubmission;
        boolean unavailableReceipt;
        boolean truncated;
        Set<String> completeFields;
        boolean mismatchedReceipt;
        final List<InteractionResponseSubmission> submissions = new ArrayList<>();
        final Map<InteractionRequestId, InteractionState> states = new HashMap<>();
        final AgentRuntime runtime = (AgentRuntime) Proxy.newProxyInstance(
                AgentRuntime.class.getClassLoader(),
                new Class<?>[] {AgentRuntime.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "pendingInteraction" -> Optional.of(pending);
                    case "toolCalls" -> List.of(call);
                    case "respond" -> {
                        if (failSubmission) throw new IllegalStateException("Controlled response failure");
                        if (unavailableReceipt)
                            throw new RuntimeContractException(
                                    RuntimeApiErrorCode.INTERACTION_NOT_FOUND, "Unavailable");
                        var value = (InteractionResponseSubmission) arguments[0];
                        submissions.add(value);
                        InteractionState state = states.getOrDefault(value.requestId(), InteractionState.RESPONDED);
                        yield new InteractionResponseReceipt(
                                mismatchedReceipt
                                        ? new InteractionResponseId("unrelated-response")
                                        : value.responseId(),
                                value.requestId(),
                                value.runId(),
                                state == InteractionState.APPLIED
                                        ? InteractionResponseReceiptStatus.DUPLICATE
                                        : InteractionResponseReceiptStatus.ACCEPTED_PENDING_APPLICATION,
                                state,
                                2,
                                3);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final CodingSessionApprovals approvals = new CodingSessionApprovals(runtime);

        Fixture() {
            next("session-1", "run-1", "file_write", Map.of());
        }

        void next(String session, String run, String name, Map<String, Object> arguments) {
            if (Set.of("file_create", "file_write", "file_diff").contains(name) && !arguments.containsKey("path")) {
                arguments = new HashMap<>(arguments);
                arguments.put("path", "/workspace/file.txt");
            }
            var runId = new AgentRunId(run);
            var callId = new ToolCallId("call-" + ++sequence);
            pending = new InteractionView(
                    new InteractionRequestId("request-" + sequence),
                    runId,
                    new AgentSessionId(session),
                    1,
                    kind,
                    InteractionState.PENDING,
                    "Approval",
                    "Allow this operation?",
                    List.of(InteractionAction.REJECT, InteractionAction.APPROVE),
                    InteractionInputContract.NONE,
                    new InteractionTargetView(
                            "tool", callId.value(), Optional.empty(), Optional.empty(), "Pending tool"),
                    new InteractionRequesterView("operator", "Operator"),
                    Instant.EPOCH,
                    Optional.empty(),
                    new InteractionConsequenceView("Allow", "Reject", "Expire"));
            call = new ToolCallView(
                    callId,
                    runId,
                    name,
                    "1.0",
                    new ToolDataView(
                            arguments,
                            truncated,
                            completeFields != null ? completeFields : truncated ? Set.of() : arguments.keySet()),
                    ToolCallStatus.WAITING_APPROVAL,
                    Optional.empty(),
                    Optional.empty(),
                    Instant.EPOCH,
                    Optional.empty(),
                    Optional.empty());
        }
    }
}
