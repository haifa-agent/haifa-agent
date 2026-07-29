package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.reference.InteractionRequestRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialExposureMode;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.credential.api.CredentialOperationRequest;
import io.haifa.agent.credential.api.CredentialReference;
import io.haifa.agent.credential.api.CredentialRequest;
import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.credential.api.SecretRedactor;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicySnapshot;
import io.haifa.agent.policy.api.PolicySnapshotRef;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.api.ResumeAgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandArguments;
import io.haifa.agent.runtime.api.RuntimeCommandId;
import io.haifa.agent.runtime.api.RuntimeCommandType;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.DefaultAgentRuntime;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptStatus;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.interaction.InteractionRequest;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.runtime.core.storage.ExecutionAttemptRepository;
import io.haifa.agent.runtime.core.storage.OutboxMessage;
import io.haifa.agent.runtime.core.storage.RuntimeOutboxPublisher;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.tool.ToolJournalState;
import io.haifa.agent.runtime.core.tool.ToolPolicyDecision;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteRuntimeRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");
    private static final TimeProvider TIME = () -> NOW;
    private static final AgentSessionId SESSION_ID = new AgentSessionId("sqlite-runtime-session");
    private static final TenantRef TENANT = new TenantRef("local");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("local-user", "user");
    private static final byte[] PROTECTOR_KEY = new byte[32];

    @TempDir
    Path directory;

    @Test
    void streamsDeltasInProcessButPersistsOnlyOneCompleteAssistantMessage() throws Exception {
        AgentChatModel streaming = new AgentChatModel() {
            @Override
            public AgentChatResponse invoke(AgentChatRequest request) {
                return finalResponse("alpha beta");
            }

            @Override
            public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
                sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 1, "alpha"));
                sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 2, " "));
                sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), 3, "beta"));
                return invoke(request);
            }
        };
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            RuntimeInstance instance = runtime(foundation, streaming, "stream-worker", new TestIds("stream"));
            AgentRunId runId =
                    instance.runtime().start(request("transient-stream")).runId();
            List<String> deltas = new java.util.concurrent.CopyOnWriteArrayList<>();
            List<io.haifa.agent.runtime.api.AgentRunOutputEventType> outputTypes =
                    new java.util.concurrent.CopyOnWriteArrayList<>();
            var subscription = instance.runtime()
                    .subscribeOutput(runId, io.haifa.agent.runtime.api.RunOutputCursor.BEFORE_FIRST, event -> {
                        outputTypes.add(event.type());
                        if (event.type() == io.haifa.agent.runtime.api.AgentRunOutputEventType.ASSISTANT_TEXT_DELTA) {
                            deltas.add(event.textDelta());
                        }
                    });

            instance.scheduler().runAll();

            assertThat(deltas).containsExactly("alpha", " ", "beta");
            assertThat(outputTypes)
                    .endsWith(io.haifa.agent.runtime.api.AgentRunOutputEventType.ASSISTANT_TEXT_COMMITTED);
            assertThat(subscription.closed()).isTrue();
            assertThat(instance.ports().state().messages(runId))
                    .filteredOn(message -> message.role() == io.haifa.agent.core.message.MessageRole.ASSISTANT)
                    .singleElement()
                    .satisfies(
                            message -> assertThat(message.contents().toString()).contains("alpha beta"));
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + directory.resolve("runtime.db").toAbsolutePath())) {
                assertThat(countWhere(
                                connection,
                                "SELECT COUNT(*) FROM runtime_event "
                                        + "WHERE type = 'model.output.assistant_text_delta'"))
                        .isZero();
                assertThat(countWhere(
                                connection, "SELECT COUNT(*) FROM runtime_event WHERE type LIKE 'model.output.%'"))
                        .isZero();
                assertThat(countWhere(
                                connection,
                                "SELECT COUNT(*) FROM session_message " + "WHERE run_id = ? AND role = 'ASSISTANT'",
                                runId.value()))
                        .isOne();
            }
        }
    }

    @Test
    void rollsBackRunEventOutboxAndDefersListenerWhenOuterStartUnitFails() throws Exception {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            RuntimePersistencePorts base = foundation.persistencePorts(protector());
            ensureSession(base);
            RuntimePersistencePorts failing = withFailingAttemptInsert(base);
            RuntimeInstance instance = runtime(failing, finalModel("unused"), "process-a", new TestIds("rollback"));
            AtomicInteger listenerCalls = new AtomicInteger();
            instance.runtime().addListener(snapshot -> listenerCalls.incrementAndGet());

            assertThatThrownBy(() -> instance.runtime().start(request("rollback")))
                    .isInstanceOf(SqliteStoreException.class)
                    .hasRootCauseMessage("injected attempt insert failure");

            assertThat(listenerCalls).hasValue(0);
            assertThat(base.outbox().pending()).isEmpty();
            try (Connection connection = foundation.connections().openConnection()) {
                assertThat(count(connection, "run")).isZero();
                assertThat(count(connection, "runtime_event")).isZero();
                assertThat(count(connection, "outbox")).isZero();
            }
        }
    }

    @Test
    void rollsBackRunAndEventWhenOutboxAppendFailsAfterEvent() throws Exception {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            RuntimePersistencePorts base = foundation.persistencePorts(protector());
            ensureSession(base);
            RuntimeInstance instance = runtime(
                    withFailingOutboxAppend(base),
                    finalModel("unused"),
                    "process-a",
                    new TestIds("event-outbox-rollback"));
            AtomicInteger listenerCalls = new AtomicInteger();
            instance.runtime().addListener(snapshot -> listenerCalls.incrementAndGet());

            assertThatThrownBy(() -> instance.runtime().start(request("event-outbox-rollback")))
                    .isInstanceOf(SqliteStoreException.class)
                    .hasRootCauseMessage("injected outbox append failure");

            assertThat(listenerCalls).hasValue(0);
            try (Connection connection = foundation.connections().openConnection()) {
                assertThat(count(connection, "run")).isZero();
                assertThat(count(connection, "runtime_event")).isZero();
                assertThat(count(connection, "outbox")).isZero();
            }
        }
    }

    @Test
    void reopensSuspendedRunRejectsSameOwnerAndRecoversFromCheckpointWithNewProcess() {
        AgentRunId runId;
        try (SqliteStoreFoundation first = SqliteTestSupport.foundation(directory)) {
            AtomicReference<DefaultAgentRuntime> runtimeRef = new AtomicReference<>();
            AtomicReference<AgentRunId> runRef = new AtomicReference<>();
            AgentChatModel pausingModel = ignored -> {
                runtimeRef.get().command(pause(runRef.get()));
                return finalResponse("checkpoint-before-restart");
            };
            RuntimeInstance processA =
                    runtime(first, pausingModel, "process-a", new TestIds("suspend-a"), builder -> builder);
            runtimeRef.set(processA.runtime());
            var accepted = processA.runtime().start(request("suspend-restart"));
            runId = accepted.runId();
            runRef.set(runId);
            processA.scheduler().runAll();

            assertThat(processA.runtime().find(runId).orElseThrow().status())
                    .as(
                            "attempts=%s events=%s steps=%s",
                            processA.ports().attempts().attemptsFor(runId).stream()
                                    .map(attempt -> Map.of(
                                            "status",
                                            attempt.status(),
                                            "error",
                                            attempt.error()
                                                    .map(Object::toString)
                                                    .orElse("")))
                                    .toList(),
                            processA.ports().events().eventsFor(runId),
                            processA.ports().state().steps(runId))
                    .isEqualTo(AgentRunStatus.SUSPENDED);
            assertThat(processA.ports().checkpoints().latest(runId)).isPresent();
        }

        try (SqliteStoreFoundation resumedStore = SqliteTestSupport.foundation(directory)) {
            RuntimeInstance processA =
                    runtime(resumedStore, finalModel("not-yet"), "process-a", new TestIds("suspend-a2"));
            processA.runtime().resume(new ResumeAgentRunRequest("resume-before-crash", runId, List.of()));
            AgentRunExecutionAttempt active =
                    resumedStore.attempts().activeFor(runId).orElseThrow();
            long expected = active.version();
            active.start("process-a", NOW);
            resumedStore.attempts().save(active, expected);

            assertThatThrownBy(() -> processA.runtime().recover(runId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("still owned");
        }

        try (SqliteStoreFoundation recoveredStore = SqliteTestSupport.foundation(directory)) {
            RuntimeInstance processB =
                    runtime(recoveredStore, finalModel("recovered"), "process-b", new TestIds("suspend-b"));
            processB.runtime().recover(runId);
            processB.scheduler().runAll();

            assertThat(processB.runtime().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(processB.ports().attempts().attemptsFor(runId))
                    .hasSize(3)
                    .extracting(AgentRunExecutionAttempt::status)
                    .containsExactly(
                            ExecutionAttemptStatus.PAUSED,
                            ExecutionAttemptStatus.ABANDONED,
                            ExecutionAttemptStatus.SUCCEEDED);
            assertThat(processB.ports().attempts().attemptsFor(runId).getLast().resumedFromCheckpointId())
                    .isPresent();
        }
    }

    @Test
    void reopensWaitingInteractionAndContinuesAfterResponse() {
        AgentRunId runId;
        InteractionRequestId interactionId = new InteractionRequestId("generic-interaction");
        try (SqliteStoreFoundation first = SqliteTestSupport.foundation(directory)) {
            RuntimeInstance processA = runtime(first, finalModel("not-run"), "process-a", new TestIds("interaction-a"));
            runId = processA.runtime().start(request("waiting-interaction")).runId();
            first.unitOfWork().execute(() -> {
                AgentRun run = first.runs().find(runId).orElseThrow();
                long runVersion = run.version();
                run.start(NOW);
                first.runs().save(run, runVersion);
                first.interactions()
                        .create(new InteractionRequest(
                                interactionId,
                                runId,
                                TENANT,
                                PRINCIPAL,
                                "clarification",
                                "Provide the missing value",
                                false,
                                NOW,
                                NOW.plus(Duration.ofHours(1))));
                runVersion = run.version();
                run.waitForInteraction(new InteractionRequestRef(interactionId.value(), "clarification"), NOW);
                first.runs().save(run, runVersion);
                AgentRunExecutionAttempt attempt =
                        first.attempts().activeFor(runId).orElseThrow();
                long attemptVersion = attempt.version();
                attempt.start("process-a", NOW);
                attempt.finish(ExecutionAttemptStatus.PAUSED, NOW, Optional.empty());
                first.attempts().save(attempt, attemptVersion);
                return null;
            });
        }

        try (SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory)) {
            RuntimeInstance processB =
                    runtime(reopened, finalModel("interaction-resumed"), "process-b", new TestIds("interaction-b"));
            processB.runtime()
                    .respond(new InteractionResponse(
                            new InteractionResponseId("generic-response"),
                            interactionId,
                            runId,
                            InteractionResponseType.CLARIFY,
                            List.of(),
                            "generic-response-key",
                            NOW));
            processB.scheduler().runAll();

            assertThat(processB.runtime().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(processB.ports().interactions().pending(runId)).isEmpty();
        }
    }

    @Test
    void reopensApprovalAndFinishesPersistedPendingResultWithoutCallingProvider() {
        AtomicInteger providerCalls = new AtomicInteger();
        AgentRunId runId;
        try (SqliteStoreFoundation first = SqliteTestSupport.foundation(directory)) {
            RuntimeInstance processA = toolRuntime(
                    first,
                    model(toolResponse()),
                    "process-a",
                    new TestIds("pending-a"),
                    providerCalls,
                    ToolPolicyDecision.REQUIRE_APPROVAL);
            runId = processA.runtime().start(request("pending-result")).runId();
            processA.scheduler().runAll();
            var interaction = processA.ports().interactions().pending(runId).orElseThrow();
            processA.runtime().respond(approvalResponse(runId, interaction.id(), "pending-approval"));
            var call = processA.ports().state().toolCalls(runId).getFirst();
            processA.ports().toolJournal().recordIntent(runId, call.idempotencyKey());
            processA.ports()
                    .toolJournal()
                    .recordPendingResult(
                            runId,
                            call.idempotencyKey(),
                            new ToolResult(true, "persisted", Map.of("value", 1), List.of(), List.of(), false));
        }

        try (SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory)) {
            RuntimeInstance processB = toolRuntime(
                    reopened,
                    finalModel("pending-result-recovered"),
                    "process-b",
                    new TestIds("pending-b"),
                    providerCalls,
                    ToolPolicyDecision.REQUIRE_APPROVAL);
            processB.runtime().recover(runId);
            processB.scheduler().runAll();

            assertThat(providerCalls).hasValue(0);
            assertThat(processB.runtime().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(processB.ports().state().toolCalls(runId).getFirst().result())
                    .hasValueSatisfying(result -> assertThat(result.summary()).isEqualTo("persisted"));
        }
    }

    @Test
    void persistsAndResumesMultipleApprovalRequiredToolsInModelOrder() {
        AtomicInteger providerCalls = new AtomicInteger();
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            RuntimeInstance instance = toolRuntime(
                    foundation,
                    model(twoToolResponse(), finalResponse("both tools completed")),
                    "process-a",
                    new TestIds("sequential-approval"),
                    providerCalls,
                    ToolPolicyDecision.REQUIRE_APPROVAL);
            AgentRunId runId =
                    instance.runtime().start(request("sequential-approval")).runId();
            instance.scheduler().runAll();
            InteractionRequest first =
                    instance.ports().interactions().pending(runId).orElseThrow();

            instance.runtime().respond(approvalResponse(runId, first.id(), "first-approval"));
            instance.scheduler().runAll();

            assertThat(instance.runtime().find(runId).orElseThrow().status())
                    .as(
                            "attempts=%s steps=%s calls=%s",
                            instance.ports().attempts().attemptsFor(runId),
                            instance.ports().state().steps(runId),
                            instance.ports().state().toolCalls(runId))
                    .isEqualTo(AgentRunStatus.WAITING_APPROVAL);
            assertThat(providerCalls).hasValue(1);
            InteractionRequest second =
                    instance.ports().interactions().pending(runId).orElseThrow();
            assertThat(second.id()).isNotEqualTo(first.id());

            instance.runtime().respond(approvalResponse(runId, second.id(), "second-approval"));
            instance.scheduler().runAll();

            assertThat(instance.runtime().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(providerCalls).hasValue(2);
            assertThat(instance.ports().state().toolCalls(runId))
                    .hasSize(2)
                    .allMatch(call -> call.status().name().equals("COMPLETED"));
            assertThat(instance.ports().attempts().attemptsFor(runId)).hasSize(3);
        }
    }

    @Test
    void continuesConversationAfterRejectedToolFromPreviousRun() {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<AgentChatRequest> nextRunRequest = new AtomicReference<>();
        AgentChatModel model = request -> {
            return switch (modelCalls.incrementAndGet()) {
                case 1 -> toolResponse();
                case 2 -> finalResponse("continued after rejection");
                case 3 -> {
                    nextRunRequest.set(request);
                    yield finalResponse("next run completed");
                }
                default -> throw new AssertionError("unexpected model call");
            };
        };
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            RuntimeInstance instance = toolRuntime(
                    foundation,
                    model,
                    "process-a",
                    new TestIds("cross-run-rejection"),
                    providerCalls,
                    ToolPolicyDecision.REQUIRE_APPROVAL);
            AgentRunId rejectedRunId =
                    instance.runtime().start(request("rejected-tool-run")).runId();
            instance.scheduler().runAll();
            InteractionRequest interaction =
                    instance.ports().interactions().pending(rejectedRunId).orElseThrow();

            instance.runtime()
                    .respond(new InteractionResponse(
                            new InteractionResponseId("rejected-response"),
                            interaction.id(),
                            rejectedRunId,
                            InteractionResponseType.REJECT,
                            List.of(),
                            "rejected-response-key",
                            NOW));
            instance.scheduler().runAll();

            assertThat(instance.runtime().find(rejectedRunId).orElseThrow().status())
                    .isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(instance.ports().state().toolCalls(rejectedRunId))
                    .singleElement()
                    .satisfies(call -> assertThat(call.status().name()).isEqualTo("DENIED"));

            AgentRunId nextRunId = instance.runtime()
                    .start(request("next-run-after-rejection"))
                    .runId();
            instance.scheduler().runAll();

            assertThat(instance.runtime().find(nextRunId).orElseThrow().status())
                    .isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(modelCalls).hasValue(3);
            assertThat(providerCalls).hasValue(0);
            assertThat(nextRunRequest.get().messages())
                    .anyMatch(message -> message.role() == ModelMessageRole.ASSISTANT
                            && message.toolCalls().stream()
                                    .anyMatch(call ->
                                            call.providerCorrelationId().value().equals("provider-tool-call")))
                    .anyMatch(message -> message.role() == ModelMessageRole.TOOL
                            && message.providerCorrelationId()
                                    .orElseThrow()
                                    .value()
                                    .equals("provider-tool-call")
                            && message.content().contains("rejected by the operator"));
        }
    }

    @Test
    void reopensOutcomeUnknownToolAndFailsClosedWithoutCallingProvider() {
        AtomicInteger providerCalls = new AtomicInteger();
        AgentRunId runId;
        try (SqliteStoreFoundation first = SqliteTestSupport.foundation(directory)) {
            RuntimeInstance processA = toolRuntime(
                    first,
                    model(toolResponse()),
                    "process-a",
                    new TestIds("unknown-a"),
                    providerCalls,
                    ToolPolicyDecision.REQUIRE_APPROVAL);
            runId = processA.runtime().start(request("outcome-unknown")).runId();
            processA.scheduler().runAll();
            var interaction = processA.ports().interactions().pending(runId).orElseThrow();
            processA.runtime().respond(approvalResponse(runId, interaction.id(), "unknown-approval"));
            var call = processA.ports().state().toolCalls(runId).getFirst();
            processA.ports().toolJournal().recordIntent(runId, call.idempotencyKey());
            processA.ports().toolJournal().recordDispatched(runId, call.idempotencyKey());
            processA.ports().toolJournal().recordUncertain(runId, call.idempotencyKey());
            assertThat(processA.ports().toolJournal().state(runId, call.idempotencyKey()))
                    .contains(ToolJournalState.OUTCOME_UNKNOWN);
        }

        try (SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory)) {
            RuntimeInstance processB = toolRuntime(
                    reopened,
                    finalModel("must-not-run"),
                    "process-b",
                    new TestIds("unknown-b"),
                    providerCalls,
                    ToolPolicyDecision.REQUIRE_APPROVAL);
            processB.runtime().recover(runId);
            processB.scheduler().runAll();

            assertThat(providerCalls).hasValue(0);
            assertThat(processB.runtime().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
        }
    }

    @Test
    void credentialBearingToolOutputIsRedactedBeforeAnySqliteWalOrShmWrite() throws Exception {
        String secret = "credential-negative-sample-7fa3c9d2";
        AtomicInteger modelCalls = new AtomicInteger();
        AgentChatModel model = ignored -> {
            if (modelCalls.incrementAndGet() == 1) {
                return new AgentChatResponse(
                        "credential-call",
                        "test-model",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("credential-tool-call"),
                                "credential_test",
                                Map.of())),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of());
            }
            return finalResponse("credential-safe-completion");
        };
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            RuntimeInstance instance = runtime(
                    foundation,
                    model,
                    "credential-worker",
                    new TestIds("credential"),
                    builder -> installCredentialTool(builder, secret));
            AgentRunId runId =
                    instance.runtime().start(request("credential-redaction")).runId();
            instance.scheduler().runAll();
            assertThat(instance.runtime().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);

            try (var paths = java.nio.file.Files.list(directory)) {
                for (Path path :
                        paths.filter(java.nio.file.Files::isRegularFile).toList()) {
                    assertThat(new String(java.nio.file.Files.readAllBytes(path), StandardCharsets.ISO_8859_1))
                            .doesNotContain(secret)
                            .doesNotContain("provider-raw-negative-sample");
                }
            }
        }
    }

    private RuntimeInstance runtime(
            SqliteStoreFoundation foundation, AgentChatModel model, String workerId, IdentifierGenerator ids) {
        return runtime(foundation, model, workerId, ids, builder -> builder);
    }

    private RuntimeInstance runtime(
            SqliteStoreFoundation foundation,
            AgentChatModel model,
            String workerId,
            IdentifierGenerator ids,
            java.util.function.UnaryOperator<RuntimeCoreBuilder> customizer) {
        RuntimePersistencePorts ports = foundation.persistencePorts(protector());
        ensureSession(ports);
        return runtime(ports, model, workerId, ids, customizer);
    }

    private RuntimeInstance runtime(
            RuntimePersistencePorts ports, AgentChatModel model, String workerId, IdentifierGenerator ids) {
        return runtime(ports, model, workerId, ids, builder -> builder);
    }

    private RuntimeInstance runtime(
            RuntimePersistencePorts ports,
            AgentChatModel model,
            String workerId,
            IdentifierGenerator ids,
            java.util.function.UnaryOperator<RuntimeCoreBuilder> customizer) {
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        RuntimeCoreBuilder builder = new RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", model)
                .scheduler(scheduler)
                .persistence(ports)
                .identifierGenerator(ids)
                .timeProvider(TIME)
                .workerId(workerId);
        return new RuntimeInstance(customizer.apply(builder).build(), scheduler, ports);
    }

    private RuntimeInstance toolRuntime(
            SqliteStoreFoundation foundation,
            AgentChatModel model,
            String workerId,
            IdentifierGenerator ids,
            AtomicInteger providerCalls,
            ToolPolicyDecision decision) {
        foundation
                .policySnapshots()
                .save(new PolicySnapshot(
                        new PolicySnapshotRef("legacy-tool-policy-v1"),
                        List.of(),
                        Optional.empty(),
                        ApprovalMode.ASK,
                        "legacy-tool-policy",
                        Optional.empty(),
                        "legacy-tool-policy-v1",
                        NOW));
        return runtime(foundation, model, workerId, ids, builder -> installTool(builder, providerCalls, decision)
                .policyStores(foundation.policyDecisions(), foundation.policyAuthorizationEvidence()));
    }

    private static RuntimeCoreBuilder installTool(
            RuntimeCoreBuilder builder, AtomicInteger providerCalls, ToolPolicyDecision decision) {
        ToolProviderId providerId = new ToolProviderId("sqlite-runtime-test");
        Map<String, Object> objectSchema =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", true);
        ToolDefinition definition = new ToolDefinition(
                new ToolName("write"),
                new SemanticVersion("1.0.0"),
                providerId,
                "write",
                "SQLite runtime recovery test tool",
                new ToolSchema("write.input", "1.0", objectSchema),
                new ToolSchema("write.output", "1.0", objectSchema),
                ToolExecutionMode.IN_PROCESS,
                true,
                Duration.ofSeconds(10),
                "test",
                ToolIdempotency.NON_IDEMPOTENT,
                ToolRisk.HIGH,
                Set.of(ToolSideEffect.FILE_WRITE),
                ToolResourceRequirements.none(),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "test",
                false,
                Set.of("test"));
        ToolProvider provider = new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return providerId;
            }

            @Override
            public ToolResult invoke(ToolInvocationRequest request) {
                providerCalls.incrementAndGet();
                return new ToolResult(true, "provider", Map.of(), List.of(), List.of(), false);
            }
        };
        var catalog = new ToolCatalogBuilder()
                .register(new ToolAlias("write"), definition, "sqlite-runtime-test", provider)
                .freeze();
        return builder.toolPolicy((run, binding, request) -> decision)
                .toolPlatform(catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator());
    }

    private static RuntimeCoreBuilder installCredentialTool(RuntimeCoreBuilder builder, String secret) {
        ToolProviderId providerId = new ToolProviderId("sqlite-credential-test");
        Map<String, Object> objectSchema =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", true);
        ToolDefinition definition = new ToolDefinition(
                new ToolName("credential.test"),
                new SemanticVersion("1.0.0"),
                providerId,
                "credential test",
                "Credential redaction persistence test tool",
                new ToolSchema("credential.input", "1.0", objectSchema),
                new ToolSchema("credential.output", "1.0", objectSchema),
                ToolExecutionMode.IN_PROCESS,
                true,
                Duration.ofSeconds(10),
                "test",
                ToolIdempotency.IDEMPOTENT,
                ToolRisk.LOW,
                Set.of(ToolSideEffect.CREDENTIAL_USE),
                ToolResourceRequirements.none(),
                List.of(new CredentialRequirement(
                        new CredentialDefinitionId("test-secret"),
                        "test",
                        Set.of("test"),
                        CredentialExposureMode.PROVIDER_CHANNEL)),
                ToolApprovalRequirement.NEVER,
                "test",
                false,
                Set.of("test"));
        ToolProvider provider = new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return providerId;
            }

            @Override
            public ToolResult invoke(ToolInvocationRequest request) {
                return new ToolResult(
                        true,
                        "provider-raw-negative-sample " + secret,
                        Map.of("token", secret),
                        List.of(),
                        List.of(),
                        false);
            }
        };
        var catalog = new ToolCatalogBuilder()
                .register(new ToolAlias("credential_test"), definition, "sqlite-credential-test", provider)
                .freeze();
        SecretRedactor redactor = value -> value == null
                ? null
                : value.replace(secret, "[REDACTED]").replace("provider-raw-negative-sample", "[REDACTED]");
        CredentialBroker broker = new CredentialBroker() {
            @Override
            public CredentialLease issue(CredentialRequest request) {
                return lease(secret);
            }

            @Override
            public CredentialLease issue(CredentialOperationRequest request) {
                return lease(secret);
            }

            @Override
            public SecretRedactor redactor() {
                return redactor;
            }
        };
        return builder.credentialBroker(broker)
                .toolPolicy((run, binding, request) -> ToolPolicyDecision.ALLOW)
                .toolPlatform(catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator());
    }

    private static CredentialLease lease(String secret) {
        return new CredentialLease() {
            private boolean closed;

            @Override
            public CredentialReference reference() {
                return new CredentialReference("test-secret-ref");
            }

            @Override
            public Instant expiresAt() {
                return NOW.plusSeconds(60);
            }

            @Override
            public boolean isClosed() {
                return closed;
            }

            @Override
            public <T> T use(io.haifa.agent.credential.api.SecretFunction<T> action) {
                if (closed) throw new IllegalStateException("lease is closed");
                return action.apply(secret.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }

    private static RuntimePersistencePorts withFailingAttemptInsert(RuntimePersistencePorts base) {
        ExecutionAttemptRepository attempts = new ExecutionAttemptRepository() {
            @Override
            public void insert(AgentRunExecutionAttempt attempt) {
                throw new IllegalStateException("injected attempt insert failure");
            }

            @Override
            public void save(AgentRunExecutionAttempt attempt, long expectedVersion) {
                base.attempts().save(attempt, expectedVersion);
            }

            @Override
            public Optional<AgentRunExecutionAttempt> find(ExecutionAttemptId id) {
                return base.attempts().find(id);
            }

            @Override
            public Optional<AgentRunExecutionAttempt> activeFor(AgentRunId runId) {
                return base.attempts().activeFor(runId);
            }

            @Override
            public List<AgentRunExecutionAttempt> attemptsFor(AgentRunId runId) {
                return base.attempts().attemptsFor(runId);
            }
        };
        return new RuntimePersistencePorts(
                base.sessions(),
                base.runs(),
                attempts,
                base.checkpoints(),
                base.state(),
                base.events(),
                base.outbox(),
                base.idempotency(),
                base.unitOfWork(),
                base.toolJournal(),
                base.interactions(),
                base.conversationSummaries(),
                base.toolResultAssets(),
                base.messageRedactions());
    }

    private static RuntimePersistencePorts withFailingOutboxAppend(RuntimePersistencePorts base) {
        RuntimeOutboxPublisher outbox = new RuntimeOutboxPublisher() {
            @Override
            public void append(OutboxMessage message) {
                throw new IllegalStateException("injected outbox append failure");
            }

            @Override
            public List<OutboxMessage> pending() {
                return base.outbox().pending();
            }

            @Override
            public void markPublished(String eventId) {
                base.outbox().markPublished(eventId);
            }

            @Override
            public boolean markConsumed(String consumerId, String eventId) {
                return base.outbox().markConsumed(consumerId, eventId);
            }
        };
        return new RuntimePersistencePorts(
                base.sessions(),
                base.runs(),
                base.attempts(),
                base.checkpoints(),
                base.state(),
                base.events(),
                outbox,
                base.idempotency(),
                base.unitOfWork(),
                base.toolJournal(),
                base.interactions(),
                base.conversationSummaries(),
                base.toolResultAssets(),
                base.messageRedactions());
    }

    private static void ensureSession(RuntimePersistencePorts ports) {
        ports.unitOfWork().execute(() -> {
            if (ports.sessions().find(SESSION_ID).isEmpty()) {
                ports.sessions()
                        .insert(AgentSession.open(
                                SESSION_ID, TENANT, PRINCIPAL, null, SessionScope.USER, NOW, Map.of()));
            }
            return null;
        });
    }

    private static AesGcmModelContinuationProtector protector() {
        return new AesGcmModelContinuationProtector(new SecretKeySpec(PROTECTOR_KEY, "AES"), new SecureRandom());
    }

    private static AgentRunRequest request(String key) {
        return new AgentRunRequest(
                key,
                new AgentDefinitionId("sqlite-runtime-agent"),
                Optional.empty(),
                "sqlite-runtime-profile",
                SESSION_ID,
                Optional.empty(),
                "test objective",
                List.of(),
                RuntimeOverrides.NONE);
    }

    private static RuntimeCommand pause(AgentRunId runId) {
        return new RuntimeCommand(
                new RuntimeCommandId("pause-command"),
                runId,
                RuntimeCommandType.PAUSE,
                RuntimeCommandArguments.NONE,
                "pause-key",
                NOW);
    }

    private static InteractionResponse approvalResponse(AgentRunId runId, InteractionRequestId requestId, String key) {
        return new InteractionResponse(
                new InteractionResponseId(key + "-response"),
                requestId,
                runId,
                InteractionResponseType.APPROVE,
                List.of(),
                key,
                NOW);
    }

    private static AgentChatModel model(AgentChatResponse... responses) {
        Queue<AgentChatResponse> queue = new ArrayDeque<>(List.of(responses));
        return ignored -> queue.remove();
    }

    private static AgentChatModel finalModel(String summary) {
        return model(finalResponse(summary));
    }

    private static AgentChatResponse finalResponse(String summary) {
        return new AgentChatResponse(
                "response-final",
                "test-model",
                summary,
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    private static AgentChatResponse toolResponse() {
        return new AgentChatResponse(
                "response-tool",
                "test-model",
                "",
                List.of(new ModelToolCall(new ProviderToolCallCorrelationId("provider-tool-call"), "write", Map.of())),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    private static AgentChatResponse twoToolResponse() {
        return new AgentChatResponse(
                "response-two-tools",
                "test-model",
                "",
                List.of(
                        new ModelToolCall(
                                new ProviderToolCallCorrelationId("provider-first-tool-call"),
                                "write",
                                Map.of("value", 1)),
                        new ModelToolCall(
                                new ProviderToolCallCorrelationId("provider-second-tool-call"),
                                "write",
                                Map.of("value", 2))),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    private static long count(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static long countWhere(Connection connection, String sql, String... parameters) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private record RuntimeInstance(
            DefaultAgentRuntime runtime, ManualExecutionScheduler scheduler, RuntimePersistencePorts ports) {}

    private static final class TestIds implements IdentifierGenerator {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        private TestIds(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String nextValue() {
            return prefix + "-" + sequence.incrementAndGet();
        }
    }
}
