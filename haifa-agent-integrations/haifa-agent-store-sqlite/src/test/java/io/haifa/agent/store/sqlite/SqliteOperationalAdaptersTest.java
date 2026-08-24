package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.RunInputId;
import io.haifa.agent.runtime.api.RunInputReceiptStatus;
import io.haifa.agent.runtime.api.RunInputSubmission;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandArguments;
import io.haifa.agent.runtime.api.RuntimeCommandId;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import io.haifa.agent.runtime.api.RuntimeCommandStatus;
import io.haifa.agent.runtime.api.RuntimeCommandType;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.interaction.InteractionRequest;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import io.haifa.agent.runtime.core.storage.OutboxMessage;
import io.haifa.agent.runtime.core.storage.RunStartIdempotencyBinding;
import io.haifa.agent.runtime.core.tool.ToolJournalState;
import io.haifa.agent.tool.api.ToolDispatchEvidence;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolReconciliationStatus;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteOperationalAdaptersTest {
    private static final Instant NOW = SqliteAggregateTestData.NOW;

    @Test
    void eventOutboxAndIdempotencyRoundTripAcrossFreshFoundation(@TempDir java.nio.file.Path directory) {
        SqliteStoreFoundation first = SqliteTestSupport.foundation(directory);
        var run = SqliteAggregateTestData.prepareRun(first);
        var event = first.events().append(run.id(), "run.started", Map.of("phase", "start"), NOW);
        OutboxMessage message = new OutboxMessage(
                event.eventId(),
                run.id(),
                event.sequence(),
                event.type(),
                OutboxMessage.CURRENT_SCHEMA_VERSION,
                event.data(),
                NOW);
        first.outbox().append(message);
        assertThat(first.idempotency().recordRun("tenant:principal", "start", "key", run.id()))
                .isEqualTo(run.id());
        assertThat(first.idempotency().markCommandApplied("tenant:principal", "command-key"))
                .isTrue();
        assertThat(first.idempotency().markCommandApplied("tenant:principal", "command-key"))
                .isFalse();
        RuntimeCommandResult commandResult = new RuntimeCommandResult(
                new RuntimeCommand(
                        new RuntimeCommandId("command"),
                        run.id(),
                        RuntimeCommandType.PAUSE,
                        RuntimeCommandArguments.NONE,
                        "command-result-key",
                        NOW),
                RuntimeCommandStatus.ACCEPTED,
                AgentRunSnapshot.from(run));
        first.idempotency().recordCommandResult("tenant:principal", "command-result-key", commandResult);
        first.idempotency().recordCommandResult("tenant:principal", "command-result-key", commandResult);

        SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory);
        assertThat(reopened.events().eventsFor(run.id())).containsExactly(event);
        assertThat(reopened.outbox().pending()).containsExactly(message);
        assertThat(reopened.idempotency().findRun("tenant:principal", "start", "key"))
                .contains(run.id());
        assertThat(reopened.idempotency().findCommandResult("tenant:principal", "command-result-key"))
                .contains(commandResult);
        assertThat(reopened.outbox().markConsumed("worker", event.eventId())).isTrue();
        assertThat(reopened.outbox().markConsumed("worker", event.eventId())).isFalse();
        reopened.outbox().markPublished(event.eventId());
        assertThat(reopened.outbox().pending()).isEmpty();
        assertThatThrownBy(() -> reopened.idempotency()
                        .recordRun("tenant:principal", "start", "key", new io.haifa.agent.core.run.AgentRunId("other")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startIdempotencyBindingPersistsRequestDigest(@TempDir java.nio.file.Path directory) {
        SqliteStoreFoundation first = SqliteTestSupport.foundation(directory);
        var run = SqliteAggregateTestData.prepareRun(first);
        var binding = new RunStartIdempotencyBinding(
                "tenant:principal", "start", "digest-key", Optional.of("digest-value"), run.id());

        assertThat(first.idempotency().recordRunBinding(binding)).isEqualTo(binding);
        SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory);
        assertThat(reopened.idempotency().findRunBinding("tenant:principal", "start", "digest-key"))
                .contains(binding);
    }

    @Test
    void journalEnforcesTransitionsAndPersistsResults(@TempDir java.nio.file.Path directory) throws Exception {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        var run = SqliteAggregateTestData.prepareRun(foundation);
        var key = new RuntimeIdempotencyKey("tool-key");
        var result = new ToolResult(true, "done", Map.of("value", 1), List.of(), List.of(), false);
        var journal = foundation.toolJournal();

        journal.recordIntent(run.id(), key, ToolIdempotency.IDEMPOTENT);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:"
                        + SqliteTestSupport.configuration(directory).databasePath());
                var statement = connection.prepareStatement(
                        "SELECT tool_idempotency FROM tool_journal WHERE run_id = ? AND idempotency_key = ?")) {
            statement.setString(1, run.id().value());
            statement.setString(2, key.value());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("IDEMPOTENT");
            }
        }
        journal.recordDispatched(run.id(), key);
        journal.recordAcknowledged(run.id(), key);
        journal.recordDispatched(run.id(), key);
        assertThat(journal.state(run.id(), key)).contains(ToolJournalState.ACKNOWLEDGED);
        journal.recordPendingResult(run.id(), key, result);
        journal.recordPendingResult(run.id(), key, result);
        assertThat(journal.pendingResult(run.id(), key)).contains(result);
        journal.recordCompleted(run.id(), key, result);
        assertThat(journal.completed(run.id(), key)).contains(result);
        assertThat(journal.state(run.id(), key)).contains(ToolJournalState.COMPLETED);
        assertThatThrownBy(() -> journal.recordFailed(run.id(), key)).isInstanceOf(IllegalStateException.class);

        var uncertainKey = new RuntimeIdempotencyKey("uncertain-key");
        journal.recordIntent(run.id(), uncertainKey);
        var dispatchEvidence = new ToolDispatchEvidence("execution-uncertain", OptionalLong.of(771), "c".repeat(64));
        journal.recordDispatched(run.id(), uncertainKey, dispatchEvidence);
        journal.recordUncertain(run.id(), uncertainKey);
        journal.recordReconciliation(
                run.id(), uncertainKey, ToolReconciliationStatus.STILL_UNKNOWN, "LOCAL_EVIDENCE_MISSING");
        assertThat(journal.hasUncertain(run.id())).isTrue();
        assertThat(journal.uncertainResult(run.id(), uncertainKey)).isEmpty();
        assertThat(journal.dispatchEvidence(run.id(), uncertainKey)).contains(dispatchEvidence);
        assertThat(journal.reconciliation(run.id(), uncertainKey)).hasValueSatisfying(reconciliation -> {
            assertThat(reconciliation.status()).isEqualTo(ToolReconciliationStatus.STILL_UNKNOWN);
            assertThat(reconciliation.reasonCode()).isEqualTo("LOCAL_EVIDENCE_MISSING");
        });

        var enrichedKey = new RuntimeIdempotencyKey("dispatch-evidence-enriched-key");
        var enrichedEvidence = new ToolDispatchEvidence("execution-enriched", OptionalLong.of(772), "d".repeat(64));
        journal.recordIntent(run.id(), enrichedKey);
        journal.recordDispatched(run.id(), enrichedKey);
        journal.recordAcknowledged(run.id(), enrichedKey);
        journal.recordDispatched(run.id(), enrichedKey, enrichedEvidence);
        assertThat(journal.state(run.id(), enrichedKey)).contains(ToolJournalState.ACKNOWLEDGED);
        assertThat(journal.dispatchEvidence(run.id(), enrichedKey)).contains(enrichedEvidence);
        assertThatThrownBy(() -> journal.recordDispatched(
                        run.id(),
                        enrichedKey,
                        new ToolDispatchEvidence("execution-conflict", OptionalLong.of(772), "d".repeat(64))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evidence changed");

        var resolvedKey = new RuntimeIdempotencyKey("reconciled-key");
        journal.recordIntent(run.id(), resolvedKey);
        journal.recordDispatched(run.id(), resolvedKey);
        journal.recordUncertain(run.id(), resolvedKey, result);
        assertThat(journal.uncertainResult(run.id(), resolvedKey)).contains(result);
        journal.recordReconciliation(
                run.id(), resolvedKey, ToolReconciliationStatus.RESOLVED, "PROCESS_TERMINAL_RESULT_CONFIRMED");
        journal.recordPendingResult(run.id(), resolvedKey, result);
        journal.recordCompleted(run.id(), resolvedKey, result);
        assertThat(journal.completed(run.id(), resolvedKey)).contains(result);

        SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory);
        assertThat(reopened.toolJournal().dispatchEvidence(run.id(), uncertainKey))
                .contains(dispatchEvidence);
        assertThat(reopened.toolJournal().reconciliation(run.id(), uncertainKey))
                .hasValueSatisfying(reconciliation ->
                        assertThat(reconciliation.reasonCode()).isEqualTo("LOCAL_EVIDENCE_MISSING"));
        assertThat(reopened.toolJournal().dispatchEvidence(run.id(), enrichedKey))
                .contains(enrichedEvidence);
        assertThat(reopened.toolJournal().completed(run.id(), resolvedKey)).contains(result);
        assertThat(reopened.toolJournal().reconciliation(run.id(), resolvedKey))
                .hasValueSatisfying(reconciliation ->
                        assertThat(reconciliation.status()).isEqualTo(ToolReconciliationStatus.RESOLVED));

        var rejectedKey = new RuntimeIdempotencyKey("pre-dispatch-rejection-key");
        var rejected = new ToolResult(
                false,
                "working directory rejected",
                Map.of("stableFailureCode", "ABSOLUTE_WORKDIR_FORBIDDEN"),
                List.of(),
                List.of(),
                false);
        journal.recordIntent(run.id(), rejectedKey);
        assertThatThrownBy(() -> journal.recordAcknowledged(run.id(), rejectedKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTENT_RECORDED -> ACKNOWLEDGED");
        journal.recordPendingResult(run.id(), rejectedKey, rejected);
        journal.recordCompleted(run.id(), rejectedKey, rejected);
        assertThat(journal.completed(run.id(), rejectedKey)).contains(rejected);
    }

    @Test
    void interactionAuthenticatesAndRecordsOnlyOneResponse(@TempDir java.nio.file.Path directory) {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        var run = SqliteAggregateTestData.prepareRun(foundation);
        var tenant = new TenantRef("tenant");
        var principal = new PrincipalRef("principal", "user");
        var request = new InteractionRequest(
                new InteractionRequestId("request"),
                run.id(),
                tenant,
                principal,
                "tool-approval",
                "Approve tool?",
                true,
                new ToolApprovalTarget(
                        new ToolCallId("tool-call"),
                        "builtin/file.write@1",
                        "sha256:definition",
                        "sha256:arguments",
                        "tenant:principal"),
                NOW,
                NOW.plusSeconds(60));
        foundation.interactions().create(request);
        var response = new InteractionResponse(
                new InteractionResponseId("response"),
                request.id(),
                run.id(),
                InteractionResponseType.APPROVE,
                List.of(new TextPart("approved", "plain")),
                "response-key",
                NOW.plusSeconds(1));

        assertThat(foundation
                        .interactions()
                        .respond(response, new RuntimeCallerContext(tenant, principal), NOW.plusSeconds(2))
                        .newlyRecorded())
                .isTrue();
        assertThat(foundation
                        .interactions()
                        .respond(response, new RuntimeCallerContext(tenant, principal), NOW.plusSeconds(2))
                        .newlyRecorded())
                .isFalse();
        assertThat(foundation.interactions().unappliedToolResolution(run.id())).isPresent();
        foundation.interactions().markResolutionApplied(request.id());
        foundation.interactions().markResolutionApplied(request.id());
        assertThat(foundation.interactions().unappliedToolResolution(run.id())).isEmpty();
    }

    @Test
    void noExpiryInteractionSurvivesReopenAndAcceptsALateResponse(@TempDir java.nio.file.Path directory) {
        SqliteStoreFoundation first = SqliteTestSupport.foundation(directory);
        var run = SqliteAggregateTestData.prepareRun(first);
        var tenant = new TenantRef("tenant");
        var principal = new PrincipalRef("principal", "user");
        var request = new InteractionRequest(
                new InteractionRequestId("no-expiry-request"),
                run.id(),
                tenant,
                principal,
                "clarification",
                "Provide a safe value",
                false,
                NOW,
                Optional.empty());
        first.interactions().create(request);

        SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory);
        Instant muchLater = NOW.plus(java.time.Duration.ofDays(365));
        assertThat(reopened.interactions().pending(run.id())).get().satisfies(pending -> assertThat(pending.expiresAt())
                .isEmpty());
        assertThat(reopened.interactions().due(run.id(), muchLater, 10)).isEmpty();
        assertThat(reopened.interactions()
                        .respond(
                                new InteractionResponseSubmission(
                                        new InteractionResponseId("late-response"),
                                        request.id(),
                                        run.id(),
                                        0,
                                        InteractionAction.SUBMIT,
                                        List.of(new TextPart("safe answer", "plain")),
                                        "late-response-key",
                                        muchLater),
                                new RuntimeCallerContext(tenant, principal),
                                muchLater)
                        .newlyRecorded())
                .isTrue();
    }

    @Test
    void concurrentConnectionsAllocateDistinctEventsAndOneInteractionResponse(@TempDir java.nio.file.Path directory)
            throws Exception {
        SqliteStoreFoundation setup = SqliteTestSupport.foundation(directory);
        var run = SqliteAggregateTestData.prepareRun(setup);
        SqliteStoreFoundation left = SqliteTestSupport.foundation(directory);
        SqliteStoreFoundation right = SqliteTestSupport.foundation(directory);

        CountDownLatch eventStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                eventStart.await();
                return left.events().append(run.id(), "event", Map.of("side", "left"), NOW);
            });
            var second = executor.submit(() -> {
                eventStart.await();
                return right.events().append(run.id(), "event", Map.of("side", "right"), NOW);
            });
            eventStart.countDown();
            assertThat(List.of(first.get().sequence(), second.get().sequence())).containsExactlyInAnyOrder(1L, 2L);
        }

        var tenant = new TenantRef("tenant");
        var principal = new PrincipalRef("principal", "user");
        var request = new InteractionRequest(
                new InteractionRequestId("concurrent-request"),
                run.id(),
                tenant,
                principal,
                "clarification",
                "Clarify",
                false,
                NOW,
                NOW.plusSeconds(60));
        setup.interactions().create(request);
        RuntimeCallerContext caller = new RuntimeCallerContext(tenant, principal);
        InteractionResponse leftResponse = new InteractionResponse(
                new InteractionResponseId("left-response"),
                request.id(),
                run.id(),
                InteractionResponseType.CLARIFY,
                List.of(new TextPart("left", "plain")),
                "left-key",
                NOW.plusSeconds(1));
        InteractionResponse rightResponse = new InteractionResponse(
                new InteractionResponseId("right-response"),
                request.id(),
                run.id(),
                InteractionResponseType.CLARIFY,
                List.of(new TextPart("right", "plain")),
                "right-key",
                NOW.plusSeconds(1));
        CountDownLatch responseStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> leftWork = () -> attemptResponse(left, leftResponse, caller, responseStart);
            Callable<Boolean> rightWork = () -> attemptResponse(right, rightResponse, caller, responseStart);
            var first = executor.submit(leftWork);
            var second = executor.submit(rightWork);
            responseStart.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void interactionAndSteerStateSurviveReopenAndApplyExactlyOnce(@TempDir java.nio.file.Path directory) {
        SqliteStoreFoundation first = SqliteTestSupport.foundation(directory);
        var run = SqliteAggregateTestData.prepareRun(first);
        var tenant = new TenantRef("tenant");
        var principal = new PrincipalRef("principal", "user");
        RuntimeCallerContext caller = new RuntimeCallerContext(tenant, principal);
        var request = new InteractionRequest(
                new InteractionRequestId("recoverable-request"),
                run.id(),
                tenant,
                principal,
                "clarification",
                "Provide a safe value",
                false,
                NOW,
                NOW.plusSeconds(60));
        first.interactions().create(request);

        SqliteStoreFoundation afterRequest = SqliteTestSupport.foundation(directory);
        assertThat(afterRequest.interactions().pendingRecord(run.id()))
                .get()
                .extracting(record -> record.revision(), record -> record.state())
                .containsExactly(0L, InteractionState.PENDING);
        InteractionResponseSubmission response = new InteractionResponseSubmission(
                new InteractionResponseId("recoverable-response"),
                request.id(),
                run.id(),
                0,
                InteractionAction.SUBMIT,
                List.of(new TextPart("safe answer", "plain")),
                "response-key",
                NOW.plusSeconds(1));
        assertThat(afterRequest
                        .interactions()
                        .respond(response, caller, NOW.plusSeconds(2))
                        .newlyRecorded())
                .isTrue();
        assertThat(afterRequest
                        .interactions()
                        .respond(response, caller, NOW.plusSeconds(2))
                        .newlyRecorded())
                .isFalse();

        SqliteStoreFoundation afterResponse = SqliteTestSupport.foundation(directory);
        assertThat(afterResponse.interactions().record(request.id())).get().satisfies(record -> {
            assertThat(record.revision()).isEqualTo(1);
            assertThat(record.state()).isEqualTo(InteractionState.RESPONDED);
            assertThat(record.action()).contains(InteractionAction.SUBMIT);
        });
        afterResponse.interactions().markResolutionApplied(request.id());
        afterResponse.interactions().markResolutionApplied(request.id());
        assertThat(SqliteTestSupport.foundation(directory).interactions().record(request.id()))
                .get()
                .extracting(record -> record.revision(), record -> record.state())
                .containsExactly(2L, InteractionState.APPLIED);

        RunInputSubmission input = new RunInputSubmission(
                new RunInputId("recoverable-input"),
                run.id(),
                OptionalLong.of(run.version()),
                List.of(new TextPart("steer safely", "plain")),
                "input-key",
                NOW.plusSeconds(3));
        SqliteStoreFoundation inputStore = SqliteTestSupport.foundation(directory);
        assertThat(inputStore
                        .runInputs()
                        .accept(input, "tenant|user|principal", NOW.plusSeconds(3))
                        .newlyAccepted())
                .isTrue();
        assertThat(inputStore
                        .runInputs()
                        .accept(input, "tenant|user|principal", NOW.plusSeconds(3))
                        .newlyAccepted())
                .isFalse();

        SqliteStoreFoundation afterInput = SqliteTestSupport.foundation(directory);
        assertThat(afterInput.runInputs().pending(run.id(), 10))
                .singleElement()
                .satisfies(record -> assertThat(record.status()).isEqualTo(RunInputReceiptStatus.ACCEPTED));
        assertThatThrownBy(() -> afterInput
                        .runInputs()
                        .accept(
                                new RunInputSubmission(
                                        new RunInputId("different-input-id"),
                                        run.id(),
                                        OptionalLong.of(run.version()),
                                        List.of(new TextPart("different", "plain")),
                                        "input-key",
                                        NOW.plusSeconds(3)),
                                "tenant|user|principal",
                                NOW.plusSeconds(3)))
                .isInstanceOf(io.haifa.agent.runtime.api.RuntimeContractException.class)
                .satisfies(exception -> assertThat(
                                ((io.haifa.agent.runtime.api.RuntimeContractException) exception).code())
                        .isEqualTo(io.haifa.agent.runtime.api.RuntimeApiErrorCode.IDEMPOTENCY_CONFLICT));

        var attempt = new AgentRunExecutionAttempt(
                new ExecutionAttemptId("input-attempt"), run.id(), 1, NOW.plusSeconds(4), Optional.empty());
        afterInput.attempts().insert(attempt);
        assertThat(afterInput
                        .runInputs()
                        .markApplied(input.inputId(), attempt.attemptId().value(), 7, NOW.plusSeconds(5))
                        .status())
                .isEqualTo(RunInputReceiptStatus.APPLIED);
        assertThat(SqliteTestSupport.foundation(directory).runInputs().find(input.inputId()))
                .get()
                .satisfies(record -> {
                    assertThat(record.status()).isEqualTo(RunInputReceiptStatus.APPLIED);
                    assertThat(record.attemptId()).contains(attempt.attemptId().value());
                    assertThat(record.iteration()).hasValue(7);
                });
    }

    private static boolean attemptResponse(
            SqliteStoreFoundation foundation,
            InteractionResponse response,
            RuntimeCallerContext caller,
            CountDownLatch start)
            throws InterruptedException {
        start.await();
        try {
            return foundation
                    .interactions()
                    .respond(response, caller, NOW.plusSeconds(2))
                    .newlyRecorded();
        } catch (IllegalStateException exception) {
            return false;
        }
    }
}
