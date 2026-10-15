package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.FrozenInstructionDiagnostic;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class FrozenInstructionDiagnosticTest {
    private static final String INSTRUCTION = "Synthetic frozen instruction A: 中文\nsecond line";

    @Test
    void diagnosticRequiresAnExactLowercaseDigestAndRejectsBodiesWithoutEchoingThem() {
        Fixture fixture = new Fixture();
        var id = fixture.start();
        var valid = fixture.runtime.frozenInstructionDiagnostic(id).orElseThrow();
        for (String malformed :
                List.of(INSTRUCTION, "SHA256:" + "0".repeat(64), "sha256:" + "A".repeat(64), "sha256:01")) {
            assertThatThrownBy(() -> new FrozenInstructionDiagnostic(
                            id,
                            valid.definitionId(),
                            valid.definitionVersion(),
                            valid.configurationSnapshot(),
                            malformed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("instructionContentHash must be a SHA-256 digest")
                    .hasNoCause();
        }
    }

    @Test
    void queuedBeforeContextAndTerminalQueriesArePureReadsOfTheSameSnapshot() throws Exception {
        Fixture fixture = new Fixture();
        var runId = fixture.start();
        int events = fixture.eventCount(runId);
        int stateCalls = fixture.otherStateCalls.get();
        var queued = fixture.runtime.frozenInstructionDiagnostic(runId).orElseThrow();
        assertThat(queued.instructionContentHash()).isEqualTo(hash(INSTRUCTION));
        assertThat(queued.runId()).isEqualTo(runId);
        assertThat(queued.definitionId()).isEqualTo(new AgentDefinitionId("test-agent"));
        assertThat(queued.definitionVersion()).isEqualTo(new AgentDefinitionVersion(1, 0, 0));
        assertThat(queued.toString()).doesNotContain(INSTRUCTION);
        assertThat(fixture.modelCalls).hasValue(0);
        assertThat(fixture.scheduler.pending()).isEqualTo(1);
        assertThat(fixture.eventCount(runId)).isEqualTo(events);
        assertThat(fixture.otherStateCalls).hasValue(stateCalls);

        fixture.scheduler.runAll();
        assertThat(fixture.runtime.find(runId).orElseThrow().status().isTerminal())
                .isTrue();
        events = fixture.eventCount(runId);
        stateCalls = fixture.otherStateCalls.get();
        int modelCalls = fixture.modelCalls.get();
        assertThat(fixture.runtime.frozenInstructionDiagnostic(runId)).contains(queued);
        assertThat(fixture.modelCalls).hasValue(modelCalls);
        assertThat(fixture.scheduler.pending()).isZero();
        assertThat(fixture.eventCount(runId)).isEqualTo(events);
        assertThat(fixture.otherStateCalls).hasValue(stateCalls);
    }

    @Test
    void existingRunIsInvisibleAcrossPrincipalAndTenantWithoutLoadingItsSnapshot() {
        Fixture fixture = new Fixture();
        var runId = fixture.start();
        assertThat(fixture.store.find(runId)).isPresent();
        fixture.read.set(reference -> {
            throw new AssertionError("invisible snapshot must not be read");
        });
        fixture.caller.set(caller("local", "another-user"));
        assertThat(fixture.runtime.frozenInstructionDiagnostic(runId)).isEmpty();
        fixture.caller.set(caller("another-tenant", "local-user"));
        assertThat(fixture.runtime.frozenInstructionDiagnostic(runId)).isEmpty();
        fixture.caller.set(caller("local", "local-user"));
        assertThat(fixture.runtime.frozenInstructionDiagnostic(new AgentRunId("missing-run")))
                .isEmpty();
        assertThat(fixture.modelCalls).hasValue(0);
        assertThat(fixture.scheduler.pending()).isEqualTo(1);
    }

    @Test
    void missingCorruptAndMismatchedSnapshotFailClosedWithoutBodyOrCause() {
        Fixture fixture = new Fixture();
        var runId = fixture.start();
        var reference = fixture.store.find(runId).orElseThrow().configurationSnapshot();
        var snapshot = fixture.store.configuration(reference).orElseThrow();
        int events = fixture.eventCount(runId);
        fixture.read.set(ignored -> Optional.empty());
        assertSafeFailure(fixture, runId);
        fixture.read.set(ignored -> {
            throw new IllegalStateException(INSTRUCTION);
        });
        assertSafeFailure(fixture, runId);
        for (var bad : List.of(
                copy(
                        snapshot,
                        new RunConfigurationSnapshotRef("different-ref", reference.contentHash()),
                        snapshot.definitionId(),
                        snapshot.definitionVersion()),
                copy(
                        snapshot,
                        new RunConfigurationSnapshotRef(reference.snapshotId(), "sha256:" + "0".repeat(64)),
                        snapshot.definitionId(),
                        snapshot.definitionVersion()),
                copy(snapshot, reference, new AgentDefinitionId("different-agent"), snapshot.definitionVersion()),
                copy(snapshot, reference, snapshot.definitionId(), new AgentDefinitionVersion(2, 0, 0)))) {
            fixture.read.set(ignored -> Optional.of(bad));
            assertSafeFailure(fixture, runId);
        }
        assertThat(fixture.eventCount(runId)).isEqualTo(events);
        assertThat(fixture.modelCalls).hasValue(0);
        assertThat(fixture.scheduler.pending()).isEqualTo(1);
    }

    private static void assertSafeFailure(Fixture fixture, AgentRunId runId) {
        assertThatThrownBy(() -> fixture.runtime.frozenInstructionDiagnostic(runId))
                .isInstanceOfSatisfying(RuntimeContractException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(RuntimeApiErrorCode.INTERNAL_ERROR);
                    assertThat(failure.getMessage()).isEqualTo("Frozen instruction diagnostic is unavailable");
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.toString()).doesNotContain(INSTRUCTION);
                });
    }

    private static RuntimeConfigurationSnapshot copy(
            RuntimeConfigurationSnapshot s,
            RunConfigurationSnapshotRef ref,
            AgentDefinitionId id,
            AgentDefinitionVersion version) {
        return new RuntimeConfigurationSnapshot(
                ref,
                id,
                version,
                s.profileId(),
                s.profileVersion(),
                s.runType(),
                s.budget(),
                s.limits(),
                s.toolBindings(),
                s.skillBindings(),
                s.skillCatalogDigest(),
                s.skillResolutionPolicyRef(),
                s.skillTrust(),
                s.allowedChildAgents(),
                s.agentInstruction(),
                s.overrides(),
                s.capabilities(),
                s.model(),
                s.modelRequestOptions(),
                s.structuredOutput());
    }

    private static String hash(String instruction) throws Exception {
        return "sha256:"
                + HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(instruction.getBytes(StandardCharsets.UTF_8)));
    }

    private static RuntimeCallerContext caller(String tenant, String principal) {
        return new RuntimeCallerContext(new TenantRef(tenant), new PrincipalRef(principal, "user"));
    }

    private static final class Fixture {
        final InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        final ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        final AtomicInteger modelCalls = new AtomicInteger();
        final AtomicInteger otherStateCalls = new AtomicInteger();
        final AtomicReference<RuntimeCallerContext> caller = new AtomicReference<>(caller("local", "local-user"));
        final AtomicReference<Function<RunConfigurationSnapshotRef, Optional<RuntimeConfigurationSnapshot>>> read =
                new AtomicReference<>(store::configuration);
        final DefaultAgentRuntime runtime;

        Fixture() {
            var ports = RuntimePersistencePorts.inMemory(store);
            var state = (RuntimeStateRepository) Proxy.newProxyInstance(
                    RuntimeStateRepository.class.getClassLoader(),
                    new Class<?>[] {RuntimeStateRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("configuration"))
                            return read.get().apply((RunConfigurationSnapshotRef) args[0]);
                        otherStateCalls.incrementAndGet();
                        try {
                            return method.invoke(store, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            var observedPorts = new RuntimePersistencePorts(
                    ports.sessions(),
                    ports.runs(),
                    ports.attempts(),
                    ports.checkpoints(),
                    state,
                    ports.events(),
                    ports.outbox(),
                    ports.idempotency(),
                    ports.unitOfWork(),
                    ports.toolJournal(),
                    ports.interactions(),
                    ports.runInputs(),
                    ports.conversationSummaries(),
                    ports.toolResultAssets(),
                    ports.messageRedactions());
            AtomicInteger ids = new AtomicInteger();
            runtime = new RuntimeCoreBuilder()
                    .callers(caller::get)
                    .scheduler(scheduler)
                    .persistence(observedPorts)
                    .identifierGenerator(() -> "diagnostic-" + ids.incrementAndGet())
                    .timeProvider(() -> Instant.parse("2026-07-21T00:00:00Z"))
                    .definitions((id, version) -> new ResolvedDefinition(
                            id, new AgentDefinitionVersion(1, 0, 0), Set.of(), Set.of(), INSTRUCTION))
                    .registerChatModel("openai-compatible", "1.0.0", request -> {
                        modelCalls.incrementAndGet();
                        return new AgentChatResponse(
                                "response",
                                "deepseek-v4-pro",
                                "done",
                                List.of(),
                                ModelFinishReason.STOP,
                                ModelUsage.unpriced(1, 1),
                                "",
                                Map.of());
                    })
                    .build();
        }

        AgentRunId start() {
            return runtime.start(new AgentRunRequest(
                            "frozen-read",
                            new AgentDefinitionId("test-agent"),
                            Optional.empty(),
                            "test-profile",
                            new AgentSessionId("session-1"),
                            Optional.empty(),
                            "synthetic objective",
                            List.of(),
                            RuntimeOverrides.NONE))
                    .runId();
        }

        int eventCount(AgentRunId id) {
            return runtime.events(id, RunEventCursor.beforeFirst(id), 100)
                    .items()
                    .size();
        }
    }
}
