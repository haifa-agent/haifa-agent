package io.haifa.agent.runtime.core.compaction;

import static io.haifa.agent.runtime.core.compaction.CompactionTestSupport.consumeModelCalls;
import static io.haifa.agent.runtime.core.compaction.CompactionTestSupport.createRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.compression.CompactionQuality;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.context.compression.ConversationSummary;
import io.haifa.agent.context.compression.ConversationSummaryRepository;
import io.haifa.agent.context.compression.DeterministicContextCompressor;
import io.haifa.agent.context.compression.SummaryId;
import io.haifa.agent.context.item.MessageGroupContextContent;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunUsageDelta;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.context.ActiveContextSnapshots;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import io.haifa.agent.runtime.core.guard.RuntimeQuotaExceededException;
import io.haifa.agent.runtime.core.lifecycle.RunAwaiter;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.loop.SessionMessageSource;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.runtime.core.storage.StorageException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SemanticCompactionFailureTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void softThresholdFailureEmitsEventAndFallsBackWhenConfigured() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator generator = () -> "compaction-failure-" + ids.incrementAndGet();
        CompressionPolicy policy = CompressionPolicy.defaults()
                .withSemanticCompactionEnabled(true)
                .withDegradedFallback(true)
                .withTailTokenBounds(5, 50);
        RunControlRegistry controls = new RunControlRegistry();
        SemanticCompactionCoordinator coordinator = coordinator(store, generator, controls, policy);
        AgentRun run = createRun(store);
        appendTurn(store, run, "u1", "a1", "first user turn", "first assistant reply " + "detail ".repeat(2_000));
        appendTurn(store, run, "u2", "a2", "second user turn", "second assistant reply " + "detail ".repeat(2_000));

        coordinator.evaluateAndCompactIfNeeded(
                run, 1, bindingWithContextWindow(store, run, request -> failure(), 4_000));

        assertThat(store.latestValid(run.sessionId())).hasValueSatisfying(summary -> assertThat(summary.quality())
                .isEqualTo(CompactionQuality.DETERMINISTIC_DEGRADED));
        assertThat(store.eventsFor(run.id()))
                .filteredOn(event -> event.type().equals("session.compaction-failed"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.data())
                            .containsEntry("reason", CompactionTriggerReason.SOFT_TOKEN_THRESHOLD.name())
                            .containsEntry("failureCategory", "MODEL_OR_RUNTIME")
                            .containsEntry("validationErrorCode", "NONE")
                            .containsEntry("physicalCalls", 1)
                            .containsEntry("degraded", true);
                });
    }

    @Test
    void cancellationIsRethrownWithoutFallbackOrFailureEvent() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator generator = () -> "compaction-cancel-" + ids.incrementAndGet();
        CompressionPolicy policy = CompressionPolicy.defaults()
                .withSemanticCompactionEnabled(true)
                .withDegradedFallback(true)
                .withTailTokenBounds(5, 50);
        RunControlRegistry controls = new RunControlRegistry();
        SemanticCompactionCoordinator coordinator = coordinator(store, generator, controls, policy);
        AgentRun run = createRun(store);
        appendTurn(store, run, "u1", "a1", "first user turn", "first assistant reply");
        appendTurn(store, run, "u2", "a2", "second user turn", "second assistant reply");
        controls.requestCancel(run.id());

        assertThatThrownBy(() -> coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> failure())))
                .isInstanceOf(CancellationObservedException.class);

        assertThat(store.latestValid(run.sessionId())).isEmpty();
        assertThat(store.eventsFor(run.id())).noneMatch(event -> event.type().equals("session.compaction-failed"));
    }

    @Test
    void validationFailureEmitsOnlyTheStableValidationCode() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator generator = () -> "compaction-validation-" + ids.incrementAndGet();
        CompressionPolicy policy = CompressionPolicy.defaults()
                .withSemanticCompactionEnabled(true)
                .withDegradedFallback(true)
                .withTailTokenBounds(5, 50);
        SemanticCompactionCoordinator coordinator = coordinator(store, generator, new RunControlRegistry(), policy);
        AgentRun run = createRun(store);
        appendTurn(store, run, "u1", "a1", "first user turn", "first assistant reply");
        appendTurn(store, run, "u2", "a2", "second user turn", "second assistant reply");

        coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> invalidJsonResponse()));

        assertThat(store.latestValid(run.sessionId())).hasValueSatisfying(summary -> assertThat(summary.quality())
                .isEqualTo(CompactionQuality.DETERMINISTIC_DEGRADED));
        assertThat(store.eventsFor(run.id()))
                .filteredOn(event -> event.type().equals("session.compaction-failed"))
                .singleElement()
                .satisfies(event -> assertThat(event.data())
                        .containsEntry("failureCategory", "VALIDATION")
                        .containsEntry("validationErrorCode", "VALIDATION_REJECTED")
                        .containsEntry("failureStage", "PARSING")
                        .containsEntry("failureCode", "JSON_PARSE_ERROR")
                        .doesNotContainValue("not-valid-json"));
    }

    @Test
    void forceCompactOnOverflowFallsBackToDeterministicEvenWhenDegradedFallbackDisabled() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator generator = () -> "compaction-overflow-" + ids.incrementAndGet();
        CompressionPolicy policy = CompressionPolicy.defaults()
                .withSemanticCompactionEnabled(true)
                .withDegradedFallback(false)
                .withTailTokenBounds(5, 50);
        RunControlRegistry controls = new RunControlRegistry();
        SemanticCompactionCoordinator coordinator = coordinator(store, generator, controls, policy);
        AgentRun run = createRun(store);
        appendTurn(store, run, "u1", "a1", "first user turn", "first assistant reply");
        appendTurn(store, run, "u2", "a2", "second user turn", "second assistant reply");

        coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> failure()));

        assertThat(store.latestValid(run.sessionId())).hasValueSatisfying(summary -> assertThat(summary.quality())
                .isEqualTo(CompactionQuality.DETERMINISTIC_DEGRADED));
        assertThat(store.eventsFor(run.id()))
                .filteredOn(event -> event.type().equals("session.compaction-failed"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.data())
                            .containsEntry("reason", CompactionTriggerReason.PROVIDER_CONTEXT_TOO_LONG.name())
                            .containsEntry("failureCategory", "MODEL_OR_RUNTIME")
                            .containsEntry("degraded", true);
                });
    }

    @Test
    void missingStructuredOutputCapabilityDegradesWithoutCallingTheModel() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator generator = () -> "compaction-capability-" + ids.incrementAndGet();
        CompressionPolicy policy = CompressionPolicy.defaults()
                .withSemanticCompactionEnabled(true)
                .withDegradedFallback(false)
                .withTailTokenBounds(5, 50);
        SemanticCompactionCoordinator coordinator = coordinator(store, generator, new RunControlRegistry(), policy);
        AgentRun run = createRun(store);
        appendTurn(store, run, "u1", "a1", "first user turn", "first assistant reply");
        appendTurn(store, run, "u2", "a2", "second user turn", "second assistant reply");
        AtomicInteger modelCalls = new AtomicInteger();

        coordinator.forceCompactOnOverflow(run, 1, bindingWithoutStructuredOutput(store, run, request -> {
            modelCalls.incrementAndGet();
            return response();
        }));

        assertThat(modelCalls).hasValue(0);
        assertThat(store.latestValid(run.sessionId())).hasValueSatisfying(summary -> assertThat(summary.quality())
                .isEqualTo(CompactionQuality.DETERMINISTIC_DEGRADED));
        assertThat(store.eventsFor(run.id()))
                .filteredOn(event -> event.type().equals("session.compaction-failed"))
                .singleElement()
                .satisfies(event -> assertThat(event.data())
                        .containsEntry("failureCategory", "MODEL_CAPABILITY")
                        .containsEntry("validationErrorCode", "STRUCTURED_OUTPUT_UNSUPPORTED")
                        .containsEntry("degraded", true));
    }

    @Test
    void deterministicRecoveryReportsTheActualRepositoryOutcome() {
        for (String fault :
                List.of("COMMITTED", "WINNER", "CONFLICT", "STORAGE", "REDACTED", "STOP", "STORAGE_EVENT")) {
            deterministicRecoveryReportsTheActualRepositoryOutcome(fault);
        }
    }

    private void deterministicRecoveryReportsTheActualRepositoryOutcome(String fault) {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator generator = () -> "recovery-" + ids.incrementAndGet();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();
        IllegalStateException storageFailure = new IllegalStateException("private storage detail");
        RunControlRegistry controls = new RunControlRegistry();
        ConversationSummaryRepository repository = (ConversationSummaryRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ConversationSummaryRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("latestSnapshot")) snapshots.incrementAndGet();
                    if (method.getName().equals("coversValidSource") && fault.equals("REDACTED")) return false;
                    if (method.getName().equals("coversValidSource") && fault.equals("STOP"))
                        controls.requestCancel(run.id());
                    if (method.getName().equals("compareAndSetValid")) {
                        writes.incrementAndGet();
                        if (fault.startsWith("STORAGE")) throw storageFailure;
                        if (fault.equals("WINNER")) {
                            ConversationSummary candidate = (ConversationSummary) args[0];
                            store.compareAndSetValid(withId(candidate, "winning-summary"), (long) args[1]);
                        }
                        if (fault.equals("WINNER") || fault.equals("CONFLICT")) {
                            throw new OptimisticLockException("private conflict detail");
                        }
                    }
                    try {
                        return method.invoke(store, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
        CompressionPolicy policy =
                CompressionPolicy.defaults().withDegradedFallback(true).withTailTokenBounds(5, 50);
        RuntimeEventAppender appender = (RuntimeEventAppender) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {RuntimeEventAppender.class}, (proxy, method, args) -> {
                    if (fault.equals("STORAGE_EVENT")
                            && method.getName().equals("append")
                            && args[1].equals("session.compaction-recovery")) {
                        throw new StorageException("secondary diagnostic store failure");
                    }
                    try {
                        return method.invoke(store, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
        SemanticCompactionCoordinator coordinator =
                coordinator(store, repository, generator, controls, policy, () -> NOW, appender);
        appendTurn(store, run, "u1", "a1", "first objective", "first evidence");
        appendTurn(store, run, "u2", "a2", "second objective", "second evidence");
        if (fault.equals("STOP")) {
            assertThatThrownBy(
                            () -> coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> failure())))
                    .isInstanceOf(CancellationObservedException.class);
            assertThat(writes).hasValue(0);
            assertThat(store.latestValid(run.sessionId())).isEmpty();
            assertThat(store.eventsFor(run.id())).noneMatch(e -> e.type().equals("session.compaction-recovery"));
            return;
        }
        if (fault.equals("STORAGE_EVENT")) {
            assertThatThrownBy(
                            () -> coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> failure())))
                    .isSameAs(storageFailure);
            assertThat(writes).hasValue(1);
            assertThat(store.latestValid(run.sessionId())).isEmpty();
            assertThat(store.eventsFor(run.id())).noneMatch(e -> e.type().equals("session.compaction-recovery"));
            return;
        }
        if (fault.equals("STORAGE")) {
            assertThatThrownBy(
                            () -> coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> failure())))
                    .isSameAs(storageFailure);
        } else {
            CompactionEvaluationOutcome outcome =
                    coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> failure()));
            assertThat(outcome.compacted()).isEqualTo(fault.equals("COMMITTED"));
        }
        String expected =
                switch (fault) {
                    case "COMMITTED" -> "DETERMINISTIC_COMMITTED";
                    case "WINNER" -> "CONCURRENT_WINNER_SELECTED";
                    case "CONFLICT" -> "CAS_CONFLICT";
                    case "REDACTED" -> "SOURCE_INVALIDATED";
                    default -> "STORAGE_FAILED";
                };
        var events = store.eventsFor(run.id());
        var failed = events.stream()
                .filter(e -> e.type().equals("session.compaction-failed"))
                .findFirst()
                .orElseThrow();
        var recovered = events.stream()
                .filter(e -> e.type().equals("session.compaction-recovery"))
                .findFirst()
                .orElseThrow();
        assertThat(recovered.data())
                .containsEntry("outcome", expected)
                .containsEntry("committed", fault.equals("COMMITTED"))
                .containsEntry("evaluationId", failed.data().get("evaluationId"))
                .doesNotContainValue("private conflict detail")
                .doesNotContainValue("private storage detail");
        assertThat(writes).hasValue(fault.equals("REDACTED") ? 0 : 1);
        assertThat(snapshots).hasValue(fault.equals("WINNER") || fault.equals("CONFLICT") ? 2 : 1);
        if (fault.equals("WINNER")) assertThat(recovered.data()).containsEntry("summaryId", "winning-summary");
        if (fault.equals("COMMITTED") || fault.equals("WINNER")) {
            ConversationSummary selected = store.latestValid(run.sessionId()).orElseThrow();
            assertThat(recovered.data())
                    .containsEntry("summaryId", selected.id().value())
                    .containsEntry("version", selected.version().value())
                    .containsEntry("quality", "DETERMINISTIC_DEGRADED");
        } else assertThat(store.latestValid(run.sessionId())).isEmpty();
    }

    @Test
    void generationAndMappingFailuresEmitOnlyTheirStableCodes() {
        for (String kind : List.of("MAPPING", "EMPTY", "FINISH")) {
            InMemoryRuntimeStore store = new InMemoryRuntimeStore();
            AgentRun run = createRun(store);
            AtomicInteger ids = new AtomicInteger();
            SemanticCompactionCoordinator coordinator = coordinator(
                    store,
                    () -> "classification-" + ids.incrementAndGet(),
                    new RunControlRegistry(),
                    CompressionPolicy.defaults().withTailTokenBounds(5, 50));
            appendTurn(store, run, "u1", "a1", "one", "evidence one");
            appendTurn(store, run, "u2", "a2", "two", "evidence two");
            AgentChatResponse invalid = new AgentChatResponse(
                    "response",
                    "model",
                    "",
                    List.of(),
                    kind.equals("FINISH") ? ModelFinishReason.LENGTH : ModelFinishReason.STOP,
                    ModelUsage.unpriced(1, 1),
                    "",
                    Map.of(),
                    Optional.empty(),
                    kind.equals("MAPPING") ? Optional.of(Map.of("schemaVersion", "v1")) : Optional.empty());
            coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> invalid));
            String expectedCode =
                    switch (kind) {
                        case "MAPPING" -> "SCHEMA_MAPPING_ERROR";
                        case "EMPTY" -> "EMPTY_STRUCTURED_OUTPUT";
                        default -> "UNEXPECTED_FINISH_REASON";
                    };
            assertThat(store.eventsFor(run.id()))
                    .filteredOn(e -> e.type().equals("session.compaction-failed"))
                    .singleElement()
                    .satisfies(e -> assertThat(e.data())
                            .containsEntry("failureCategory", "VALIDATION")
                            .containsEntry("failureStage", kind.equals("MAPPING") ? "MAPPING" : "GENERATION")
                            .containsEntry("failureCode", expectedCode));
        }
    }

    @Test
    void separateEvaluationsInOneRunHaveDistinctCorrelationIds() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicInteger ids = new AtomicInteger();
        SemanticCompactionCoordinator coordinator = coordinator(
                store,
                () -> "id-" + ids.incrementAndGet(),
                new RunControlRegistry(),
                CompressionPolicy.defaults().withDegradedFallback(true).withTailTokenBounds(5, 50));
        appendTurn(store, run, "u1", "a1", "one", "evidence one");
        appendTurn(store, run, "u2", "a2", "two", "evidence two");
        coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> failure()));
        appendTurn(store, run, "u3", "a3", "three", "evidence three");
        coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> failure()));
        var failures = store.eventsFor(run.id()).stream()
                .filter(e -> e.type().equals("session.compaction-failed"))
                .toList();
        assertThat(failures).hasSize(2);
        assertThat(failures.get(0).data().get("evaluationId"))
                .isNotEqualTo(failures.get(1).data().get("evaluationId"));
    }

    @Test
    void runCallLimitEscapesBothTargetAndOverflowFallback() {
        for (boolean target : List.of(false, true)) {
            runCallLimitEscapesBothTargetAndOverflowFallback(target);
        }
    }

    private void runCallLimitEscapesBothTargetAndOverflowFallback(boolean target) {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        consumeModelCalls(store, run, run.limits().maxModelCalls());
        AtomicInteger ids = new AtomicInteger();
        CompressionPolicy policy = CompressionPolicy.defaults()
                .withDegradedFallback(true)
                .withActiveHistoryBudgetTokens(10)
                .withTailTokenBounds(5, 10);
        SemanticCompactionCoordinator coordinator =
                coordinator(store, () -> "hard-" + ids.incrementAndGet(), new RunControlRegistry(), policy);
        appendTurn(store, run, "u1", "a1", "one", "evidence one");
        appendTurn(store, run, "u2", "a2", "two", "evidence two");
        FrozenModelBinding binding = binding(store, run, request -> failure());
        assertThatThrownBy(() -> {
                    if (target) coordinator.evaluateAndCompactIfNeeded(run, 1, binding);
                    else coordinator.forceCompactOnOverflow(run, 1, binding);
                })
                .isInstanceOf(RuntimeLimitExceededException.class)
                .hasFieldOrPropertyWithValue("resource", "modelCalls");
        assertThat(store.latestValid(run.sessionId())).isEmpty();
        assertThat(store.eventsFor(run.id())).noneMatch(e -> e.type().equals("session.compaction-failed"));
    }

    @Test
    void observedStopAfterAResponsePreventsSummaryCommit() {
        for (boolean deadline : List.of(false, true)) {
            observedStopAfterAResponsePreventsSummaryCommit(deadline);
        }
    }

    private void observedStopAfterAResponsePreventsSummaryCommit(boolean deadline) {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicReference<Instant> clock = new AtomicReference<>(NOW);
        RunControlRegistry controls = new RunControlRegistry();
        AtomicInteger ids = new AtomicInteger();
        SemanticCompactionCoordinator coordinator = coordinator(
                store,
                store,
                () -> "stop-" + ids.incrementAndGet(),
                controls,
                CompressionPolicy.defaults().withDegradedFallback(true).withTailTokenBounds(5, 50),
                clock::get);
        appendTurn(store, run, "u1", "a1", "one", "evidence one");
        appendTurn(store, run, "u2", "a2", "two", "evidence two");
        assertThatThrownBy(() -> coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> {
                    if (deadline) clock.set(NOW.plusMillis(run.limits().maxWallTimeMillis()));
                    else controls.requestCancel(run.id());
                    return validSummaryResponse();
                })))
                .isInstanceOf(deadline ? RuntimeLimitExceededException.class : CancellationObservedException.class);
        assertThat(store.latestValid(run.sessionId())).isEmpty();
        assertThat(run.usage().inputTokens()).isEqualTo(10);
        assertThat(store.eventsFor(run.id())).noneMatch(e -> e.type().equals("session.compaction-failed"));
    }

    @Test
    void lastAdmittedSummaryCallCanCommit() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        consumeModelCalls(store, run, run.limits().maxModelCalls() - 1);
        AtomicInteger ids = new AtomicInteger();
        SemanticCompactionCoordinator coordinator = coordinator(
                store,
                () -> "last-" + ids.incrementAndGet(),
                new RunControlRegistry(),
                CompressionPolicy.defaults().withTailTokenBounds(5, 50));
        appendTurn(store, run, "u1", "a1", "one", "evidence one");
        appendTurn(store, run, "u2", "a2", "two", "evidence two");
        assertThat(coordinator
                        .forceCompactOnOverflow(run, 1, binding(store, run, request -> validSummaryResponse()))
                        .compacted())
                .isTrue();
        assertThat(store.latestValid(run.sessionId()))
                .hasValueSatisfying(s -> assertThat(s.quality()).isEqualTo(CompactionQuality.SEMANTIC_VALIDATED));
    }

    @Test
    void exhaustedBatchAllowanceIsAPlanningFailureNotInvalidModelContent() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicInteger ids = new AtomicInteger();
        SemanticCompactionCoordinator coordinator = coordinator(
                store,
                () -> "batch-" + ids.incrementAndGet(),
                new RunControlRegistry(),
                CompressionPolicy.defaults().withTailTokenBounds(5, 10));
        for (int i = 0; i < 70; i++) appendTurn(store, run, "u-" + i, "a-" + i, "objective " + i, "evidence " + i);
        coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> validSummaryResponse()));
        assertThat(store.eventsFor(run.id()))
                .filteredOn(e -> e.type().equals("session.compaction-failed"))
                .singleElement()
                .satisfies(e -> assertThat(e.data())
                        .containsEntry("failureCategory", "PLAN_BUDGET")
                        .containsEntry("validationErrorCode", "NONE")
                        .containsEntry("failureStage", "PLANNING")
                        .containsEntry("failureCode", "PHYSICAL_CALL_ALLOWANCE")
                        .containsEntry("physicalCalls", 0));
        assertThat(store.latestVersion(run.sessionId())).isZero();
    }

    @Test
    void cumulativeRunQuotaIsNotConvertedToCompactionFallback() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        CompactionTestSupport.recordUsage(
                store, run, new AgentRunUsageDelta(run.budget().maxInputTokens() + 1, 0, 0, 0, 0, 0, 0, 0));
        AtomicInteger ids = new AtomicInteger();
        SemanticCompactionCoordinator coordinator = coordinator(
                store,
                () -> "quota-" + ids.incrementAndGet(),
                new RunControlRegistry(),
                CompressionPolicy.defaults().withDegradedFallback(true).withTailTokenBounds(5, 50));
        appendTurn(store, run, "u1", "a1", "one", "evidence one");
        appendTurn(store, run, "u2", "a2", "two", "evidence two");
        assertThatThrownBy(() -> coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> failure())))
                .isInstanceOf(RuntimeQuotaExceededException.class)
                .hasFieldOrPropertyWithValue("resource", "inputTokens");
        assertThat(run.usage().modelCalls()).isZero();
        assertThat(store.latestValid(run.sessionId())).isEmpty();
        assertThat(store.eventsFor(run.id())).noneMatch(e -> e.type().equals("session.compaction-failed"));
    }

    @Test
    void typedStorageFailureDuringGenerationIsNotConvertedToFallback() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicInteger ids = new AtomicInteger();
        SemanticCompactionCoordinator coordinator = coordinator(
                store,
                () -> "storage-" + ids.incrementAndGet(),
                new RunControlRegistry(),
                CompressionPolicy.defaults().withDegradedFallback(true).withTailTokenBounds(5, 50));
        appendTurn(store, run, "u1", "a1", "one", "evidence one");
        appendTurn(store, run, "u2", "a2", "two", "evidence two");
        StorageException storageFailure = new StorageException("private accounting store failure");
        assertThatThrownBy(() -> coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> {
                    throw storageFailure;
                })))
                .isSameAs(storageFailure);
        assertThat(store.latestValid(run.sessionId())).isEmpty();
        assertThat(store.eventsFor(run.id())).noneMatch(e -> e.type().equals("session.compaction-failed"));
    }

    @Test
    void parseAndMappingErrorsCanRegenerateOnceWithoutEchoingInvalidOutput() {
        for (boolean mapping : List.of(false, true)) {
            InMemoryRuntimeStore store = new InMemoryRuntimeStore();
            AgentRun run = createRun(store);
            AtomicInteger ids = new AtomicInteger();
            SemanticCompactionCoordinator coordinator = coordinator(
                    store,
                    () -> "correct-" + ids.incrementAndGet(),
                    new RunControlRegistry(),
                    CompressionPolicy.defaults().withTailTokenBounds(5, 50));
            appendTurn(store, run, "u1", "a1", "one", "evidence one");
            appendTurn(store, run, "u2", "a2", "two", "evidence two");
            List<AgentChatRequest> requests = new ArrayList<>();
            coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> {
                requests.add(request);
                if (requests.size() == 1)
                    return new AgentChatResponse(
                            "bad",
                            "model",
                            "NEVER_ECHO_INVALID_OUTPUT",
                            List.of(),
                            ModelFinishReason.STOP,
                            ModelUsage.unpriced(1, 1),
                            "",
                            Map.of(),
                            Optional.empty(),
                            mapping
                                    ? Optional.of(Map.of("schemaVersion", "NEVER_ECHO_INVALID_OUTPUT"))
                                    : Optional.empty());
                return validSummaryResponse();
            }));
            assertThat(requests.size()).isEqualTo(2);
            String correction = requests.get(1).messages().getLast().content();
            assertThat(correction.contains(mapping ? "SCHEMA_MAPPING_ERROR" : "JSON_PARSE_ERROR"))
                    .isTrue();
            assertThat(correction.contains("NEVER_ECHO_INVALID_OUTPUT")).isFalse();
            assertThat(store.latestValid(run.sessionId()))
                    .hasValueSatisfying(s -> assertThat(s.quality()).isEqualTo(CompactionQuality.SEMANTIC_VALIDATED));
        }
    }

    @Test
    void correctionNeverReplaysAnUnsafeCandidateAndStopsAfterOneAttempt() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicInteger ids = new AtomicInteger();
        SemanticCompactionCoordinator coordinator = coordinator(
                store,
                () -> "safe-" + ids.incrementAndGet(),
                new RunControlRegistry(),
                CompressionPolicy.defaults().withTailTokenBounds(5, 50));
        appendTurn(store, run, "u1", "a1", "one", "evidence one");
        appendTurn(store, run, "u2", "a2", "two", "evidence two");
        AtomicInteger calls = new AtomicInteger();
        coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> {
            if (calls.incrementAndGet() == 2) {
                String correction = request.messages().getLast().content();
                assertThat(correction.contains("SAFETY")).isTrue();
                assertThat(correction.contains("NEVER_ECHO_UNSAFE")).isFalse();
            }
            return validSummaryResponse("NEVER_ECHO_UNSAFE <think>");
        }));
        assertThat(calls).hasValue(2);
        assertThat(store.latestValid(run.sessionId()))
                .hasValueSatisfying(s -> assertThat(s.semanticSummary()).isEmpty());
    }

    @Test
    void repairCannotConsumeCallsNeededForRemainingBatches() {
        for (boolean firstFails : List.of(false, true)) {
            InMemoryRuntimeStore store = new InMemoryRuntimeStore();
            AgentRun run = createRun(store);
            AtomicInteger ids = new AtomicInteger();
            CompressionPolicy policy = physicalCalls(2).withTailTokenBounds(5, 10);
            SemanticCompactionCoordinator coordinator =
                    coordinator(store, () -> "quota-" + ids.incrementAndGet(), new RunControlRegistry(), policy);
            for (int i = 0; i < 30; i++) appendTurn(store, run, "u-" + i, "a-" + i, "objective " + i, "evidence " + i);
            AtomicInteger calls = new AtomicInteger();
            coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> {
                int call = calls.incrementAndGet();
                return firstFails && call == 1 ? invalidJsonResponse() : validSummaryResponse();
            }));
            assertThat(calls).hasValue(firstFails ? 1 : 2);
            assertThat(store.latestValid(run.sessionId())).hasValueSatisfying(s -> assertThat(s.quality())
                    .isEqualTo(
                            firstFails
                                    ? CompactionQuality.DETERMINISTIC_DEGRADED
                                    : CompactionQuality.SEMANTIC_VALIDATED));
        }
    }

    @Test
    void aGrowingIntermediateSummaryIsCheckedBeforeTheNextDispatch() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicInteger ids = new AtomicInteger();
        SemanticCompactionCoordinator coordinator = coordinator(
                store,
                () -> "grow-" + ids.incrementAndGet(),
                new RunControlRegistry(),
                CompressionPolicy.defaults().withTailTokenBounds(5, 10));
        for (int i = 0; i < 30; i++) appendTurn(store, run, "u-" + i, "a-" + i, "objective " + i, "evidence " + i);
        AtomicInteger calls = new AtomicInteger();
        coordinator.forceCompactOnOverflow(
                run,
                1,
                bindingWithWindowAndOutput(
                        store,
                        run,
                        request -> {
                            calls.incrementAndGet();
                            assertThat(SummaryModelInvoker.estimatedInput(
                                            request.messages().getFirst().content(),
                                            request.messages().getLast().content()))
                                    .isLessThanOrEqualTo(3_208L);
                            return validSummaryResponse("x".repeat(9_000));
                        },
                        12_000,
                        8_192));
        assertThat(calls).hasValue(1);
        assertThat(store.latestVersion(run.sessionId())).isZero();
        assertThat(store.eventsFor(run.id()))
                .filteredOn(e -> e.type().equals("session.compaction-failed"))
                .singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("failureCode", "SUMMARY_INPUT_BUDGET"));
    }

    @Test
    void aWholeTurnThatCannotFitIsRejectedWithoutDispatchOrCoverage() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicInteger ids = new AtomicInteger();
        SemanticCompactionCoordinator coordinator = coordinator(
                store,
                () -> "large-" + ids.incrementAndGet(),
                new RunControlRegistry(),
                CompressionPolicy.defaults().withTailTokenBounds(5, 10));
        appendTurn(store, run, "u1", "a1", "x".repeat(4_000), "y".repeat(4_000));
        appendTurn(store, run, "u2", "a2", "two", "evidence two");
        AtomicInteger calls = new AtomicInteger();
        coordinator.forceCompactOnOverflow(
                run,
                1,
                bindingWithWindowAndOutput(
                        store,
                        run,
                        request -> {
                            calls.incrementAndGet();
                            return validSummaryResponse();
                        },
                        2_000,
                        256));
        assertThat(calls).hasValue(0);
        assertThat(store.latestVersion(run.sessionId())).isZero();
    }

    @Test
    void redactedSourceCannotBeReusedForCorrection() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicInteger ids = new AtomicInteger();
        SemanticCompactionCoordinator coordinator = coordinator(
                store,
                () -> "revoke-" + ids.incrementAndGet(),
                new RunControlRegistry(),
                CompressionPolicy.defaults().withTailTokenBounds(5, 50));
        appendTurn(store, run, "u1", "a1", "one", "evidence one");
        appendTurn(store, run, "u2", "a2", "two", "evidence two");
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> {
                    calls.incrementAndGet();
                    store.redactMessage(new AgentMessageId("u1"));
                    return invalidJsonResponse();
                })))
                .isInstanceOf(OptimisticLockException.class);
        assertThat(calls).hasValue(1);
        assertThat(store.latestVersion(run.sessionId())).isZero();
    }

    @Test
    void targetFailureBacksOffAcrossRunsAndExpiresButForceStillEvaluates() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicInteger ids = new AtomicInteger();
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        CompressionPolicy policy =
                CompressionPolicy.defaults().withActiveHistoryBudgetTokens(1000).withTailTokenBounds(5, 50);
        SemanticCompactionCoordinator coordinator = coordinator(
                store, store, () -> "backoff-" + ids.incrementAndGet(), new RunControlRegistry(), policy, now::get);
        appendTurn(store, run, "u1", "a1", "u", "x".repeat(3300));
        appendTurn(store, run, "u2", "a2", "u", "a");
        AtomicInteger calls = new AtomicInteger();
        FrozenModelBinding binding = bindingWithContextWindow(
                store,
                run,
                request -> {
                    calls.incrementAndGet();
                    return invalidJsonResponse();
                },
                100_000);
        coordinator.evaluateAndCompactIfNeeded(run, 1, binding);
        assertThat(calls).hasValue(2);
        AgentRun next = CompactionTestSupport.createRun(store, new AtomicInteger(1000));
        coordinator.evaluateAndCompactIfNeeded(next, 1, binding);
        assertThat(calls).hasValue(2);
        assertThat(store.latestValid(run.sessionId())).isEmpty();
        now.set(NOW.plusSeconds(60));
        coordinator.evaluateAndCompactIfNeeded(next, 2, binding);
        assertThat(calls).hasValue(4);
        // Recreating the owner on the same authoritative Store intentionally loses transient backoff.
        coordinator(store, store, () -> "reopened-" + ids.incrementAndGet(), new RunControlRegistry(), policy, now::get)
                .evaluateAndCompactIfNeeded(next, 3, binding);
        assertThat(calls).hasValue(6);
        coordinator.forceCompactOnOverflow(next, 3, binding);
        assertThat(calls).hasValue(8);
        assertThat(store.latestValid(run.sessionId())).hasValueSatisfying(summary -> assertThat(summary.quality())
                .isEqualTo(CompactionQuality.DETERMINISTIC_DEGRADED));
    }

    @Test
    void failedRecoveryChecksTheExactRetentionBoundaryBeforeContinuation() {
        for (int historyTokens : List.of(1200, 1201)) {
            InMemoryRuntimeStore store = new InMemoryRuntimeStore();
            AgentRun run = createRun(store);
            AtomicInteger ids = new AtomicInteger();
            CompressionPolicy policy = CompressionPolicy.defaults()
                    .withActiveHistoryBudgetTokens(1000)
                    .withTailTokenBounds(5, 50);
            SemanticCompactionCoordinator coordinator =
                    coordinator(store, () -> "bound-" + ids.incrementAndGet(), new RunControlRegistry(), policy);
            // Initial input contributes six tokens; the other three one-character messages contribute one each.
            String evidence = "x".repeat((historyTokens - 9) * 3);
            appendTurn(store, run, "u1", "a1", "u", evidence);
            appendTurn(store, run, "u2", "a2", "u", "a");
            var source =
                    store.messagesAfter(run.sessionId(), io.haifa.agent.core.message.MessageCursor.BEFORE_FIRST, 10);
            var projection =
                    new io.haifa.agent.runtime.core.model.ModelMessageProjectionPlanner(store).plan(source, id -> null);
            assertThat(projection.projectedActiveTokens()).isEqualTo(historyTokens);
            FrozenModelBinding binding =
                    bindingWithContextWindow(store, run, request -> invalidJsonResponse(), 100_000);
            if (historyTokens == 1200) {
                coordinator.evaluateAndCompactIfNeeded(run, 1, binding);
            } else {
                assertThatThrownBy(() -> coordinator.evaluateAndCompactIfNeeded(run, 1, binding))
                        .isInstanceOf(ContextBuildException.class);
            }
            assertThat(store.latestValid(run.sessionId())).isEmpty();
            assertThat(store.message(new AgentMessageId("a1")).orElseThrow().contents())
                    .containsExactly(new TextPart(evidence, "plain"));
        }
    }

    @Test
    void runtimeKeepsOriginalInputAfterSemanticCommitAndAfterBoundedFailure() {
        for (String recovery : List.of("semantic", "bounded", "forced", "oversized")) {
            boolean successfulSummary = recovery.equals("semantic") || recovery.equals("oversized");
            InMemoryRuntimeStore store = new InMemoryRuntimeStore();
            AtomicInteger ids = new AtomicInteger();
            var scheduler = new ManualExecutionScheduler();
            List<AgentChatRequest> ordinary = new ArrayList<>();
            AtomicInteger summaries = new AtomicInteger();
            int window = io.haifa.agent.runtime.core.bootstrap.DefaultResolvedModelSnapshots.deepSeekV4Pro()
                    .contextWindow();
            String original =
                    "ORIGINAL_RUN_REQUEST_PROBE " + "q".repeat(recovery.equals("oversized") ? window * 3 : 3100);
            AgentChatModel model = request -> {
                if (request.structuredOutput().isPresent()) {
                    summaries.incrementAndGet();
                    return successfulSummary ? validSummaryResponse() : invalidJsonResponse();
                }
                ordinary.add(request);
                return new AgentChatResponse(
                        "final",
                        "model",
                        "done",
                        List.of(),
                        ModelFinishReason.STOP,
                        ModelUsage.unpriced(10, 10),
                        "",
                        Map.of());
            };
            var runtime = new RuntimeCoreBuilder()
                    .persistence(RuntimePersistencePorts.inMemory(store))
                    .scheduler(scheduler)
                    .identifierGenerator(() -> "wired-" + ids.incrementAndGet())
                    .timeProvider(() -> NOW)
                    .compressionPolicy(CompressionPolicy.defaults()
                            .withActiveHistoryBudgetTokens(1000)
                            .withTailTokenBounds(5, 50))
                    .registerChatModel("openai-compatible", "1.0.0", model)
                    .build();
            var accepted = runtime.start(new AgentRunRequest(
                    "wired-compaction",
                    new AgentDefinitionId("compaction-failure-agent"),
                    Optional.empty(),
                    "default",
                    new AgentSessionId("wired-session"),
                    Optional.empty(),
                    original,
                    List.of(),
                    RuntimeOverrides.NONE));
            AgentRun run = store.find(accepted.runId()).orElseThrow();
            var originalMessage = store.initiatingUserMessage(run.id()).orElseThrow();
            appendTurn(store, run, "u1", "a1", "later user input", "answer");
            appendTurn(store, run, "u2", "a2", "newest user input", "answer");
            if (recovery.equals("forced")) {
                coordinator(
                                store,
                                () -> "forced-" + ids.incrementAndGet(),
                                new RunControlRegistry(),
                                CompressionPolicy.defaults()
                                        .withDegradedFallback(true)
                                        .withTailTokenBounds(5, 50))
                        .forceCompactOnOverflow(run, 1, binding(store, run, model));
            }
            scheduler.runAll();
            if (recovery.equals("oversized")) {
                assertThat(ordinary.size()).isZero();
                assertThat(store.find(run.id()).orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
                continue;
            }
            assertThat(ordinary.size()).isEqualTo(1);
            assertThat(ordinary.getFirst().runId()).isEqualTo(run.id());
            assertThat(ordinary.getFirst().messages().stream()
                            .filter(message -> message.role() == ModelMessageRole.USER
                                    && message.content().equals(original))
                            .count())
                    .isEqualTo(1);
            assertThat(store.find(run.id()).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(summaries).hasValue(successfulSummary ? 1 : 2);
            if (!recovery.equals("bounded")) {
                ConversationSummary selected =
                        store.latestValid(run.sessionId()).orElseThrow();
                assertThat(selected.coveredThrough().compareTo(originalMessage.cursor()))
                        .isGreaterThanOrEqualTo(0);
                var compressor = new DeterministicContextCompressor();
                var policy = CompressionPolicy.defaults();
                var source = new SessionMessageSource(
                        store,
                        store,
                        compressor,
                        policy,
                        () -> "unused",
                        () -> NOW,
                        new ActiveContextSnapshots(store, store, policy, compressor));
                var selection = source.select(run);
                assertThat(selection.summary()).contains(selected);
                assertThat(selection.items().stream()
                                .filter(item -> item.content() instanceof MessageGroupContextContent)
                                .flatMap(item -> ((MessageGroupContextContent) item.content()).messages().stream())
                                .filter(message -> message.id().equals(originalMessage.id()))
                                .count())
                        .isEqualTo(1);
                if (successfulSummary) {
                    assertThat(ordinary.getFirst().messages().stream()
                                    .anyMatch(message -> message.content().contains("Keep the objective")))
                            .isTrue();
                } else {
                    assertThat(selected.quality()).isEqualTo(CompactionQuality.DETERMINISTIC_DEGRADED);
                }
            } else {
                assertThat(store.latestValid(run.sessionId())).isEmpty();
            }
        }
    }

    @Test
    void deterministicRecoveryRemainsLossyAndDoesNotRemoveOriginalEvidence() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicInteger ids = new AtomicInteger();
        CompressionPolicy policy =
                CompressionPolicy.defaults().withDegradedFallback(true).withTailTokenBounds(5, 50);
        assertThat(CompressionPolicy.defaults().allowDeterministicDegradedFallback())
                .isFalse();
        String longEvidence = "x".repeat(241) + "REQUIRED_AFTER_240";
        store.appendSessionMessage(draft("long-evidence", run, MessageRole.USER, longEvidence));
        for (int n = 1; n <= 33; n++) {
            store.appendSessionMessage(
                    draft("evidence-" + n, run, MessageRole.ASSISTANT, n == 33 ? "REQUIRED_ITEM_33" : "fact " + n));
        }
        store.appendSessionMessage(draft("retained-user", run, MessageRole.USER, "next request"));
        SemanticCompactionCoordinator coordinator =
                coordinator(store, () -> "lossy-" + ids.incrementAndGet(), new RunControlRegistry(), policy);
        coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> invalidJsonResponse()));
        ConversationSummary summary = store.latestValid(run.sessionId()).orElseThrow();
        assertThat(summary.quality()).isEqualTo(CompactionQuality.DETERMINISTIC_DEGRADED);
        assertThat(summary.facts().size()).isEqualTo(32);
        assertThat(summary.facts().stream()
                        .anyMatch(fact -> fact.contains("REQUIRED_AFTER_240") || fact.contains("REQUIRED_ITEM_33")))
                .isFalse();
        assertThat(store.message(new AgentMessageId("long-evidence"))
                        .orElseThrow()
                        .contents())
                .containsExactly(new TextPart(longEvidence, "plain"));
        assertThat(store.message(new AgentMessageId("evidence-33"))
                        .orElseThrow()
                        .contents())
                .containsExactly(new TextPart("REQUIRED_ITEM_33", "plain"));
    }

    @Test
    void aLargePriorSummaryCannotBypassRetentionWithOnlyOneNewInputGroup() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger ids = new AtomicInteger();
        AgentRun previous = CompactionTestSupport.createRun(store, ids);
        var source = store.initiatingUserMessage(previous.id()).orElseThrow();
        var policy = CompressionPolicy.defaults().withActiveHistoryBudgetTokens(1000);
        var compressor = new DeterministicContextCompressor();
        var seed = compressor
                .compress(new io.haifa.agent.context.compression.CompressionRequest(
                        new SummaryId("prior-summary"),
                        new io.haifa.agent.context.compression.SummaryVersion(1),
                        previous.sessionId(),
                        List.of(source),
                        32,
                        NOW,
                        policy.version()))
                .summary();
        store.compareAndSet(
                new ConversationSummary(
                        seed.id(),
                        seed.version(),
                        seed.sessionId(),
                        seed.coveredFrom(),
                        seed.coveredThrough(),
                        seed.sourceMessageIds(),
                        seed.sourceHash(),
                        List.of("x".repeat(4500)),
                        List.of(),
                        List.of(),
                        List.of(),
                        1500,
                        NOW,
                        seed.policyVersion(),
                        seed.compressorVersion(),
                        seed.securityLabels(),
                        true),
                0);
        AgentRun next = CompactionTestSupport.createRun(store, ids);
        SemanticCompactionCoordinator coordinator =
                coordinator(store, () -> "unused-" + ids.incrementAndGet(), new RunControlRegistry(), policy);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> coordinator.evaluateAndCompactIfNeeded(
                        next,
                        1,
                        bindingWithContextWindow(
                                store,
                                next,
                                request -> {
                                    calls.incrementAndGet();
                                    return validSummaryResponse();
                                },
                                100_000)))
                .isInstanceOf(ContextBuildException.class);
        assertThat(calls).hasValue(0);
        assertThat(store.latestVersion(next.sessionId())).isEqualTo(1);
    }

    @Test
    void nonRecoverableNormalizedModelFailureRetainsItsCategoryWithoutRetryOrDegradation() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicInteger ids = new AtomicInteger();
        CompressionPolicy policy =
                CompressionPolicy.defaults().withActiveHistoryBudgetTokens(1000).withTailTokenBounds(5, 50);
        SemanticCompactionCoordinator coordinator =
                coordinator(store, () -> "auth-" + ids.incrementAndGet(), new RunControlRegistry(), policy);
        appendTurn(store, run, "u1", "a1", "u", "x".repeat(3300));
        appendTurn(store, run, "u2", "a2", "u", "a");
        var failure = new io.haifa.agent.model.api.ModelInvocationException(
                io.haifa.agent.model.api.ModelErrorCategory.AUTHENTICATION_FAILED,
                false,
                401,
                "authentication",
                new io.haifa.agent.model.api.ModelCallId("normalized-auth-call"),
                "Authentication failed",
                null);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> coordinator.evaluateAndCompactIfNeeded(
                        run,
                        1,
                        bindingWithContextWindow(
                                store,
                                run,
                                request -> {
                                    calls.incrementAndGet();
                                    throw failure;
                                },
                                100_000)))
                .isSameAs(failure);
        assertThat(calls).hasValue(1);
        assertThat(store.latestValid(run.sessionId())).isEmpty();
    }

    private static CompressionPolicy physicalCalls(int maximum) {
        CompressionPolicy p = CompressionPolicy.defaults();
        return new CompressionPolicy(
                p.recentMessageGroups(),
                p.maxSummaryFacts(),
                p.forcedRecentMessageGroups(),
                p.retainedTailTokenPercent(),
                p.forcedRetainedTailTokenPercent(),
                p.softTriggerHeadroomPercent(),
                p.minTriggerHeadroom(),
                p.maxTriggerHeadroom(),
                p.targetTailTokenPercent(),
                p.minTailTokens(),
                p.maxTailTokens(),
                maximum,
                p.allowDeterministicDegradedFallback(),
                p.semanticCompactionEnabled(),
                p.activeHistoryBudgetPercent(),
                p.minActiveHistoryBudgetTokens(),
                p.maxActiveHistoryBudgetTokens(),
                p.activeHistoryBudgetTokens());
    }

    private static ConversationSummary withId(ConversationSummary s, String id) {
        return new ConversationSummary(
                new SummaryId(id),
                s.version(),
                s.sessionId(),
                s.coveredFrom(),
                s.coveredThrough(),
                s.sourceMessageIds(),
                s.coveredSourceCount(),
                s.sourceHash(),
                s.facts(),
                s.decisions(),
                s.openItems(),
                s.toolOutcomeReferences(),
                s.estimatedTokens(),
                s.createdAt(),
                s.policyVersion(),
                s.compressorVersion(),
                s.securityLabels(),
                s.valid(),
                s.semanticSummary(),
                s.quality());
    }

    private static AgentChatResponse validSummaryResponse() {
        return validSummaryResponse("Keep the objective");
    }

    private static AgentChatResponse validSummaryResponse(String text) {
        return new AgentChatResponse(
                "summary",
                "model",
                "",
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(10, 10),
                "",
                Map.of(),
                Optional.empty(),
                Optional.of(Map.of(
                        "schemaVersion",
                        "v1",
                        "language",
                        "en",
                        "goals",
                        List.of(Map.of(
                                "stableItemId",
                                "g1",
                                "text",
                                text,
                                "sourceRefs",
                                List.of("m001"),
                                "confidence",
                                "OBSERVED")),
                        "constraints",
                        List.of(),
                        "progress",
                        Map.of("completed", List.of(), "active", List.of(), "blocked", List.of()),
                        "decisions",
                        List.of(),
                        "nextSteps",
                        List.of(),
                        "criticalContext",
                        List.of(),
                        "unresolvedQuestions",
                        List.of())));
    }

    private static SemanticCompactionCoordinator coordinator(
            InMemoryRuntimeStore store,
            IdentifierGenerator ids,
            RunControlRegistry controls,
            CompressionPolicy policy) {
        return coordinator(store, store, ids, controls, policy, () -> NOW);
    }

    private static SemanticCompactionCoordinator coordinator(
            InMemoryRuntimeStore store,
            ConversationSummaryRepository summaries,
            IdentifierGenerator ids,
            RunControlRegistry controls,
            CompressionPolicy policy,
            TimeProvider time) {
        return coordinator(store, summaries, ids, controls, policy, time, store);
    }

    private static SemanticCompactionCoordinator coordinator(
            InMemoryRuntimeStore store,
            ConversationSummaryRepository summaries,
            IdentifierGenerator ids,
            RunControlRegistry controls,
            CompressionPolicy policy,
            TimeProvider time,
            RuntimeEventAppender events) {
        RunTransitionCoordinator transitions =
                new RunTransitionCoordinator(store, store, store, store, ids, time, new RunAwaiter(), store);
        return new SemanticCompactionCoordinator(
                store,
                summaries,
                new SummaryModelInvoker(transitions, controls, ids, time, policy),
                new CompactionTriggerEvaluator(policy),
                policy,
                new DeterministicContextCompressor(),
                ids,
                time,
                events);
    }

    private static void appendTurn(
            InMemoryRuntimeStore store,
            AgentRun run,
            String userId,
            String assistantId,
            String userText,
            String assistantText) {
        store.appendSessionMessage(draft(userId, run, MessageRole.USER, userText));
        store.appendSessionMessage(draft(assistantId, run, MessageRole.ASSISTANT, assistantText));
    }

    private static SessionMessageDraft draft(String id, AgentRun run, MessageRole role, String text) {
        return new SessionMessageDraft(
                new AgentMessageId(id),
                run.sessionId(),
                Optional.of(new AgentRunId(run.id().value())),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                role == MessageRole.USER ? MessageVisibility.USER_VISIBLE : MessageVisibility.AGENT_VISIBLE,
                List.of(new TextPart(text, "plain")),
                Map.of(),
                NOW);
    }

    private static FrozenModelBinding binding(InMemoryRuntimeStore store, AgentRun run, AgentChatModel model) {
        return new FrozenModelBinding(
                store.configuration(run.configurationSnapshot()).orElseThrow(), model, List.of());
    }

    private static FrozenModelBinding bindingWithoutStructuredOutput(
            InMemoryRuntimeStore store, AgentRun run, AgentChatModel model) {
        RuntimeConfigurationSnapshot configuration =
                store.configuration(run.configurationSnapshot()).orElseThrow();
        ResolvedModelSnapshot original = configuration.model();
        Set<ModelCapability> withoutStructuredOutput = original.capabilities().stream()
                .filter(capability -> capability != ModelCapability.STRUCTURED_OUTPUT)
                .collect(Collectors.toUnmodifiableSet());
        ResolvedModelSnapshot downgraded = ResolvedModelSnapshot.create(
                original.providerId(),
                original.providerVersion(),
                original.modelId(),
                original.modelVersion(),
                original.providerModelId(),
                original.adapterType(),
                original.adapterVersion(),
                original.apiStyle(),
                original.dialect(),
                original.endpoint(),
                original.credentialRef(),
                original.nativeStreaming(),
                withoutStructuredOutput,
                original.contextWindow(),
                original.maxOutputTokens(),
                original.providerOptions(),
                original.invocationOptions());
        return new FrozenModelBinding(configuration.withModel(downgraded), model, List.of());
    }

    private static FrozenModelBinding bindingWithContextWindow(
            InMemoryRuntimeStore store, AgentRun run, AgentChatModel model, int contextWindow) {
        return bindingWithWindowAndOutput(store, run, model, contextWindow, 256);
    }

    private static FrozenModelBinding bindingWithWindowAndOutput(
            InMemoryRuntimeStore store, AgentRun run, AgentChatModel model, int contextWindow, int output) {
        RuntimeConfigurationSnapshot configuration =
                store.configuration(run.configurationSnapshot()).orElseThrow();
        ResolvedModelSnapshot original = configuration.model();
        ResolvedModelSnapshot resized = ResolvedModelSnapshot.create(
                original.providerId(),
                original.providerVersion(),
                original.modelId(),
                original.modelVersion(),
                original.providerModelId(),
                original.adapterType(),
                original.adapterVersion(),
                original.apiStyle(),
                original.dialect(),
                original.endpoint(),
                original.credentialRef(),
                original.nativeStreaming(),
                original.capabilities(),
                contextWindow,
                output,
                original.providerOptions(),
                original.invocationOptions());
        return new FrozenModelBinding(configuration.withModel(resized), model, List.of());
    }

    private static AgentChatResponse response() {
        return new AgentChatResponse(
                "test-response",
                "test-model",
                "done",
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    private static AgentChatResponse failure() {
        throw new RuntimeException("injected model failure");
    }

    private static AgentChatResponse invalidJsonResponse() {
        return new AgentChatResponse(
                "invalid-response",
                "test-model",
                "not-valid-json",
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }
}
