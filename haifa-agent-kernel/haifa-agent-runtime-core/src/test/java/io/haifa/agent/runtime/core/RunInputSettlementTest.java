package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RunInputId;
import io.haifa.agent.runtime.api.RunInputReceipt;
import io.haifa.agent.runtime.api.RunInputReceiptStatus;
import io.haifa.agent.runtime.api.RunInputSubmission;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.input.InMemoryRunInputPort;
import io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimeEvent;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.tool.InMemoryToolExecutionJournal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Accepted steer input is applied at a safe point or observably rejected; it is never silently lost. */
class RunInputSettlementTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void inputAcceptedWhileTheModelAnswersDefersCompletionUntilTheModelSawIt() {
        List<AgentChatRequest> requests = new CopyOnWriteArrayList<>();
        AtomicReference<Fixture> fixtureRef = new AtomicReference<>();
        AtomicReference<AgentRunId> runRef = new AtomicReference<>();
        Fixture fixture = fixture(request -> {
            requests.add(request);
            if (requests.size() == 1) {
                assertThat(fixtureRef
                                .get()
                                .runtime()
                                .submitInput(steer(runRef.get(), "steer-1", "Also cover Hangzhou"))
                                .status())
                        .isEqualTo(RunInputReceiptStatus.ACCEPTED);
                return answer("first answer");
            }
            return answer("revised answer");
        });
        fixtureRef.set(fixture);
        AgentRunId runId = fixture.runtime().start(request("deferred-final")).runId();
        runRef.set(runId);

        fixture.scheduler().runAll();

        var completed = fixture.runtime().find(runId).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.output()).contains("revised answer");
        assertThat(requests).hasSize(2);
        List<ModelMessage> second = requests.get(1).messages();
        int superseded = indexOf(second, ModelMessageRole.ASSISTANT, "first answer");
        int steerAt = indexOf(second, ModelMessageRole.USER, "Also cover Hangzhou");
        assertThat(superseded).isNotNegative();
        assertThat(steerAt).isGreaterThan(superseded);
        var record = fixture.runInputs().find(new RunInputId("input-steer-1")).orElseThrow();
        assertThat(record.status()).isEqualTo(RunInputReceiptStatus.APPLIED);
        assertThat(record.iteration()).hasValue(2);
        assertThat(types(fixture, runId))
                .containsSubsequence("run.input.accepted", "completion.deferred", "run.input.applied", "run.completed");
        assertThat(fixture.runtime()
                        .submitInput(steer(runId, "steer-1", "Also cover Hangzhou"))
                        .status())
                .as("a retry after application reports the authoritative state")
                .isEqualTo(RunInputReceiptStatus.APPLIED);
    }

    @Test
    void retriesWithAFreshSubmissionTimeAreDuplicatesButChangedContentConflicts() {
        Fixture fixture = fixture(request -> answer("done"));
        AgentRunId runId = fixture.runtime().start(request("duplicate")).runId();

        assertThat(fixture.runtime().submitInput(steer(runId, "key", "focus")).status())
                .isEqualTo(RunInputReceiptStatus.ACCEPTED);
        RunInputSubmission retry = new RunInputSubmission(
                new RunInputId("input-retry"),
                runId,
                OptionalLong.empty(),
                List.of(new TextPart("focus", "plain")),
                "key",
                NOW.plusSeconds(30));
        RunInputReceipt duplicate = fixture.runtime().submitInput(retry);
        assertThat(duplicate.status()).isEqualTo(RunInputReceiptStatus.DUPLICATE);
        assertThat(duplicate.inputId()).isEqualTo(new RunInputId("input-key"));
        assertThatThrownBy(() -> fixture.runtime().submitInput(steer(runId, "key", "something else")))
                .isInstanceOf(RuntimeContractException.class)
                .extracting("code")
                .isEqualTo(RuntimeApiErrorCode.IDEMPOTENCY_CONFLICT);

        fixture.scheduler().runAll();

        assertThat(fixture.store().messages(runId).stream()
                        .filter(message -> message.metadata().containsKey("runInputId"))
                        .count())
                .as("a duplicate submission is applied once")
                .isEqualTo(1);
    }

    @Test
    void terminalRunRefusesNewInputButStillReportsBoundInput() {
        Fixture fixture = fixture(request -> answer("done"));
        AgentRunId runId = fixture.runtime().start(request("terminal")).runId();
        fixture.runtime().submitInput(steer(runId, "before", "early note"));
        fixture.scheduler().runAll();
        assertThat(fixture.runtime().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);

        assertThatThrownBy(() -> fixture.runtime().submitInput(steer(runId, "after", "too late")))
                .isInstanceOf(RuntimeContractException.class)
                .extracting("code")
                .isEqualTo(RuntimeApiErrorCode.RUN_STATE_CONFLICT);
        assertThat(fixture.runtime()
                        .submitInput(steer(runId, "before", "early note"))
                        .status())
                .isEqualTo(RunInputReceiptStatus.APPLIED);
    }

    @Test
    void stoppingARunSettlesPendingInputAsRejectedBeforeTheTerminalEvent() {
        Fixture fixture = fixture(request -> answer("never"));
        AgentRunId runId = fixture.runtime().start(request("cancel")).runId();
        fixture.runtime().submitInput(steer(runId, "pending", "please continue"));

        fixture.runtime().handle(runId).cancel();

        assertThat(fixture.runtime().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.CANCELLED);
        RunInputReceipt settled = fixture.runtime().submitInput(steer(runId, "pending", "please continue"));
        assertThat(settled.status()).isEqualTo(RunInputReceiptStatus.REJECTED);
        assertThat(settled.reasonCode()).contains("run-cancelled");
        List<RuntimeEvent> events = fixture.store().eventsFor(runId);
        assertThat(events)
                .filteredOn(event -> event.type().equals("run.input.rejected"))
                .singleElement()
                .satisfies(event -> assertThat(event.data())
                        .containsEntry("inputId", "input-pending")
                        .containsEntry("reasonCode", "run-cancelled"));
        assertThat(types(fixture, runId)).containsSubsequence("run.input.rejected", "run.cancelled");
        assertThat(fixture.runInputs().pending(runId, 10)).isEmpty();
    }

    @Test
    void finalAnswerOnTheLastAllowedModelCallCompletesAndRejectsTheInputItCannotShow() {
        AtomicReference<Fixture> fixtureRef = new AtomicReference<>();
        AtomicReference<AgentRunId> runRef = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = fixture(request -> {
            calls.incrementAndGet();
            fixtureRef.get().runtime().submitInput(steer(runRef.get(), "late", "one more thing"));
            return answer("only answer");
        });
        fixtureRef.set(fixture);
        AgentRunId runId = fixture.runtime()
                .start(request("last-call", Map.of("maxModelCalls", 1)))
                .runId();
        runRef.set(runId);

        fixture.scheduler().runAll();

        var completed = fixture.runtime().find(runId).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.output()).contains("only answer");
        assertThat(calls).hasValue(1);
        assertThat(fixture.runInputs().find(new RunInputId("input-late")).orElseThrow())
                .satisfies(record -> {
                    assertThat(record.status()).isEqualTo(RunInputReceiptStatus.REJECTED);
                    assertThat(record.reasonCode()).contains("run-completed");
                });
    }

    @Test
    void concurrentFinalAndInputEitherRejectOrApplyButNeverLoseAcceptedInput() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            int accepted = 0;
            int refused = 0;
            for (int round = 0; round < 40; round++) {
                CountDownLatch answering = new CountDownLatch(1);
                Fixture fixture = fixture(request -> {
                    answering.countDown();
                    return answer("answer");
                });
                AgentRunId runId =
                        fixture.runtime().start(request("race-" + round)).runId();
                Future<?> loop = executor.submit(() -> fixture.scheduler().runAll());
                assertThat(answering.await(5, TimeUnit.SECONDS)).isTrue();
                Optional<RunInputReceipt> receipt;
                try {
                    receipt = Optional.of(fixture.runtime().submitInput(steer(runId, "race", "late steer")));
                } catch (RuntimeContractException stateConflict) {
                    assertThat(stateConflict.code()).isEqualTo(RuntimeApiErrorCode.RUN_STATE_CONFLICT);
                    receipt = Optional.empty();
                }
                loop.get(10, TimeUnit.SECONDS);
                fixture.scheduler().runAll();

                assertThat(fixture.runtime().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(fixture.runInputs().pending(runId, 10))
                        .as("no accepted input may remain pending on a terminal run")
                        .isEmpty();
                if (receipt.isPresent()) {
                    accepted++;
                    assertThat(receipt.orElseThrow().status()).isEqualTo(RunInputReceiptStatus.ACCEPTED);
                    assertThat(fixture.runInputs()
                                    .find(receipt.orElseThrow().inputId())
                                    .orElseThrow()
                                    .status())
                            .isEqualTo(RunInputReceiptStatus.APPLIED);
                } else {
                    refused++;
                    assertThat(fixture.runInputs().find(new RunInputId("input-race")))
                            .isEmpty();
                }
            }
            assertThat(accepted + refused).isEqualTo(40);
        } finally {
            executor.shutdownNow();
        }
    }

    private static int indexOf(List<ModelMessage> messages, ModelMessageRole role, String content) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).role() == role
                    && messages.get(index).content().contains(content)) return index;
        }
        return -1;
    }

    private static List<String> types(Fixture fixture, AgentRunId runId) {
        return fixture.store().eventsFor(runId).stream().map(RuntimeEvent::type).toList();
    }

    private static RunInputSubmission steer(AgentRunId runId, String key, String text) {
        return new RunInputSubmission(
                new RunInputId("input-" + key),
                runId,
                OptionalLong.empty(),
                List.of(new TextPart(text, "plain")),
                key,
                NOW);
    }

    private static AgentChatResponse answer(String text) {
        return new AgentChatResponse(
                "response-" + text.hashCode(),
                "deepseek-v4-pro",
                text,
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    private static AgentRunRequest request(String key) {
        return request(key, Map.of());
    }

    private static AgentRunRequest request(String key, Map<String, Object> overrides) {
        return new AgentRunRequest(
                key,
                new AgentDefinitionId("test-agent"),
                Optional.empty(),
                "test-profile",
                new AgentSessionId("session-" + key),
                Optional.empty(),
                "test objective",
                List.of(),
                overrides.isEmpty()
                        ? RuntimeOverrides.NONE
                        : new RuntimeOverrides("runtime.overrides", "1.0", overrides));
    }

    private static Fixture fixture(Function<AgentChatRequest, AgentChatResponse> behavior) {
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        InMemoryRunInputPort runInputs = new InMemoryRunInputPort();
        AtomicInteger sequence = new AtomicInteger();
        IdentifierGenerator ids = () -> "id-" + sequence.incrementAndGet();
        AgentChatModel model = behavior::apply;
        DefaultAgentRuntime runtime = new RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", model)
                .scheduler(scheduler)
                .persistence(RuntimePersistencePorts.inMemory(
                        store, new InMemoryToolExecutionJournal(), new InMemoryInteractionPort()))
                .runInputs(runInputs)
                .identifierGenerator(ids)
                .timeProvider(() -> NOW)
                .build();
        return new Fixture(runtime, scheduler, store, runInputs);
    }

    private record Fixture(
            DefaultAgentRuntime runtime,
            ManualExecutionScheduler scheduler,
            InMemoryRuntimeStore store,
            InMemoryRunInputPort runInputs) {}
}
