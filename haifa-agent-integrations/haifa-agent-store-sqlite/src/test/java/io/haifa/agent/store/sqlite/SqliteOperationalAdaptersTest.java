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
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandArguments;
import io.haifa.agent.runtime.api.RuntimeCommandId;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import io.haifa.agent.runtime.api.RuntimeCommandStatus;
import io.haifa.agent.runtime.api.RuntimeCommandType;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.interaction.InteractionRequest;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import io.haifa.agent.runtime.core.storage.OutboxMessage;
import io.haifa.agent.runtime.core.tool.ToolJournalState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
                "event-1",
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
        assertThat(reopened.outbox().markConsumed("worker", "event-1")).isTrue();
        assertThat(reopened.outbox().markConsumed("worker", "event-1")).isFalse();
        reopened.outbox().markPublished("event-1");
        assertThat(reopened.outbox().pending()).isEmpty();
        assertThatThrownBy(() -> reopened.idempotency()
                        .recordRun("tenant:principal", "start", "key", new io.haifa.agent.core.run.AgentRunId("other")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void journalEnforcesTransitionsAndPersistsResults(@TempDir java.nio.file.Path directory) {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        var run = SqliteAggregateTestData.prepareRun(foundation);
        var key = new RuntimeIdempotencyKey("tool-key");
        var result = new ToolResult(true, "done", Map.of("value", 1), List.of(), List.of(), false);
        var journal = foundation.toolJournal();

        journal.recordIntent(run.id(), key);
        journal.recordDispatched(run.id(), key);
        journal.recordAcknowledged(run.id(), key);
        journal.recordPendingResult(run.id(), key, result);
        journal.recordPendingResult(run.id(), key, result);
        assertThat(journal.pendingResult(run.id(), key)).contains(result);
        journal.recordCompleted(run.id(), key, result);
        assertThat(journal.completed(run.id(), key)).contains(result);
        assertThat(journal.state(run.id(), key)).contains(ToolJournalState.COMPLETED);
        assertThatThrownBy(() -> journal.recordFailed(run.id(), key)).isInstanceOf(IllegalStateException.class);

        var uncertainKey = new RuntimeIdempotencyKey("uncertain-key");
        journal.recordIntent(run.id(), uncertainKey);
        journal.recordDispatched(run.id(), uncertainKey);
        journal.recordUncertain(run.id(), uncertainKey);
        assertThat(journal.hasUncertain(run.id())).isTrue();
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
