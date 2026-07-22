package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepType;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.bootstrap.ContentAddressedSnapshotFactory;
import io.haifa.agent.runtime.core.bootstrap.DefaultResolvedModelSnapshots;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExternalModelRuntimeTest {
    @Test
    void frozenExternalModelCompletesNativeToolCallLoopWithCorrelation() {
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        List<RuntimeTraceEvent> traces = new CopyOnWriteArrayList<>();
        IdentifierGenerator identifierGenerator = () -> "external-id-" + ids.incrementAndGet();
        TimeProvider time = () -> Instant.parse("2026-07-21T00:00:00Z");
        var chatModel = (io.haifa.agent.model.api.AgentChatModel) request -> {
            int call = calls.incrementAndGet();
            assertThat(request.model().providerId().value()).isEqualTo("deepseek");
            assertThat(request.model().providerModelId()).isEqualTo("deepseek-v4-pro");
            if (call == 1) {
                assertThat(request.tools()).singleElement().satisfies(tool -> {
                    assertThat(tool.name()).isEqualTo("echo");
                    assertThat(tool.inputJsonSchema()).containsEntry("type", "object");
                    assertThat(tool.strict()).isFalse();
                });
                return new AgentChatResponse(
                        "response-1",
                        "deepseek-v4-pro",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("provider-call-1"), "echo", Map.of("text", "hello"))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(10, 3),
                        "fp-test",
                        Map.of());
            }
            assertThat(request.messages())
                    .anyMatch(message -> message.role() == ModelMessageRole.ASSISTANT
                            && message.toolCalls().stream().anyMatch(toolCall -> toolCall.providerCorrelationId()
                                    .value()
                                    .equals("provider-call-1")))
                    .anyMatch(message -> message.role() == ModelMessageRole.TOOL
                            && message.providerCorrelationId()
                                    .orElseThrow()
                                    .value()
                                    .equals("provider-call-1")
                            && message.content().equals("echoed: hello")
                            && message.toolResultData().equals(Map.of("text", "hello"))
                            && !message.toolResultTruncated());
            return new AgentChatResponse(
                    "response-2",
                    "deepseek-v4-pro",
                    "done",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(15, 4),
                    "fp-test",
                    Map.of());
        };
        RuntimeCoreBuilder builder =
                new RuntimeCoreBuilder().registerChatModel("openai-compatible", "1.0.0", chatModel);
        DefaultAgentRuntime runtime = TestToolPlatform.install(
                        builder,
                        "echo",
                        "1.0.0",
                        "echo.input",
                        false,
                        request -> new ToolResult(
                                true,
                                "echoed: " + request.arguments().values().get("text"),
                                request.arguments().values(),
                                List.of(),
                                List.of(),
                                false))
                .scheduler(scheduler)
                .store(store)
                .identifierGenerator(identifierGenerator)
                .timeProvider(time)
                .trace(traces::add)
                .build();

        var accepted = runtime.start(request("external-run"));
        var frozen = store.configuration(
                        store.find(accepted.runId()).orElseThrow().configurationSnapshot())
                .orElseThrow();
        String frozenDigest = frozen.model().configurationDigest();
        scheduler.runAll();

        assertThat(runtime.find(accepted.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(runtime.find(accepted.runId()).orElseThrow().output()).contains("done");
        assertThat(calls).hasValue(2);
        assertThat(store.find(accepted.runId()).orElseThrow().usage().inputTokens())
                .isEqualTo(25);
        assertThat(store.find(accepted.runId()).orElseThrow().usage().outputTokens())
                .isEqualTo(7);
        assertThat(store.messages(accepted.runId()).stream().flatMap(message -> message.contents().stream()))
                .anyMatch(ToolCallPart.class::isInstance)
                .anyMatch(ToolResultPart.class::isInstance);
        assertThat(store.steps(accepted.runId()).stream()
                        .filter(step -> step.type() == AgentStepType.MODEL_CALL)
                        .map(step -> step.result().orElseThrow().data()))
                .hasSize(2)
                .allSatisfy(metadata -> {
                    assertThat(metadata).containsEntry("providerId", "deepseek");
                    assertThat(metadata).containsEntry("modelId", "deepseek-v4-pro");
                    assertThat(metadata).containsKeys("modelCallId", "finishReason", "inputTokens", "outputTokens");
                });
        assertThat(traces)
                .filteredOn(trace -> trace.operation().equals("model.invoke"))
                .hasSize(2)
                .allSatisfy(trace -> assertThat(trace.safeAttributes())
                        .containsEntry("providerId", "deepseek")
                        .containsKeys("modelCallId", "responseId", "finishReason"));
        assertThat(store.configuration(
                                store.find(accepted.runId()).orElseThrow().configurationSnapshot())
                        .orElseThrow()
                        .model()
                        .configurationDigest())
                .isEqualTo(frozenDigest);
    }

    @Test
    void rejectsUndisclosedProviderToolAndRecordsNormalizedErrorTrace() {
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger ids = new AtomicInteger();
        List<RuntimeTraceEvent> traces = new CopyOnWriteArrayList<>();
        var runtime = new RuntimeCoreBuilder()
                .registerChatModel(
                        "openai-compatible",
                        "1.0.0",
                        request -> new AgentChatResponse(
                                "response-unknown-tool",
                                "deepseek-v4-pro",
                                "",
                                List.of(new ModelToolCall(
                                        new ProviderToolCallCorrelationId("provider-call-unknown"),
                                        "not-disclosed",
                                        Map.of())),
                                ModelFinishReason.TOOL_CALLS,
                                ModelUsage.unpriced(1, 1),
                                "",
                                Map.of()))
                .scheduler(scheduler)
                .store(store)
                .identifierGenerator(() -> "unknown-tool-id-" + ids.incrementAndGet())
                .timeProvider(() -> Instant.parse("2026-07-21T00:00:00Z"))
                .trace(traces::add)
                .build();

        var accepted = runtime.start(request("unknown-tool-run"));
        scheduler.runAll();

        assertThat(runtime.find(accepted.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(store.steps(accepted.runId()))
                .filteredOn(step -> step.type() == AgentStepType.MODEL_CALL)
                .singleElement()
                .satisfies(step -> assertThat(step.error()).isPresent());
        assertThat(traces)
                .filteredOn(trace -> trace.operation().equals("model.error"))
                .singleElement()
                .satisfies(trace -> assertThat(trace.safeAttributes())
                        .containsEntry("category", "MALFORMED_RESPONSE")
                        .containsEntry("providerCode", "undisclosed_tool")
                        .containsEntry("providerId", "deepseek"));
    }

    @Test
    void builderExposesOnlyVersionedModelApiAssembly() {
        assertThat(List.of(RuntimeCoreBuilder.class.getMethods()))
                .noneMatch(method -> method.getName().equals("modelClient"))
                .noneMatch(method -> method.getName().equals("modelSelector"))
                .filteredOn(method -> method.getName().equals("registerChatModel"))
                .singleElement()
                .satisfies(method -> assertThat(method.getParameterTypes())
                        .containsExactly(String.class, String.class, io.haifa.agent.model.api.AgentChatModel.class));
    }

    @Test
    void missingFrozenAdapterVersionFailsWithoutFallingBack() {
        var defaults = DefaultResolvedModelSnapshots.deepSeekV4Pro();
        var unavailable = io.haifa.agent.model.api.ResolvedModelSnapshot.create(
                defaults.providerId(),
                defaults.providerVersion(),
                defaults.modelId(),
                defaults.modelVersion(),
                defaults.providerModelId(),
                defaults.adapterType(),
                "9.9.9",
                defaults.endpoint(),
                defaults.credentialRef(),
                defaults.capabilities(),
                defaults.contextWindow(),
                defaults.maxOutputTokens(),
                defaults.providerOptions(),
                defaults.invocationOptions());
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        AtomicInteger calls = new AtomicInteger();
        var runtime = new RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", request -> {
                    calls.incrementAndGet();
                    return new AgentChatResponse(
                            "unexpected",
                            "model",
                            "unexpected",
                            List.of(),
                            ModelFinishReason.STOP,
                            ModelUsage.unpriced(0, 0),
                            "",
                            Map.of());
                })
                .profiles((id, overrides) -> new ResolvedProfile(
                        id,
                        "1.0",
                        AgentRunType.CHAT,
                        new AgentRunBudget(1000, 1000, 1000, 10, 10, 2, "USD", 1000),
                        new AgentRunLimits(10, 2, 1, 60_000, 30_000),
                        unavailable))
                .scheduler(scheduler)
                .build();

        var accepted = runtime.start(request("missing-adapter"));
        scheduler.runAll();

        assertThat(calls).hasValue(0);
        assertThat(runtime.find(accepted.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
    }

    @Test
    void modelSelectionIsPartOfContentAddressedRunConfiguration() {
        var defaults = DefaultResolvedModelSnapshots.deepSeekV4Pro();
        var alternative = io.haifa.agent.model.api.ResolvedModelSnapshot.create(
                defaults.providerId(),
                defaults.providerVersion(),
                new ModelDefinitionId("deepseek-v4-flash"),
                "model-v2",
                "deepseek-v4-flash",
                defaults.adapterType(),
                defaults.adapterVersion(),
                defaults.endpoint(),
                defaults.credentialRef(),
                defaults.capabilities(),
                defaults.contextWindow(),
                defaults.maxOutputTokens(),
                defaults.providerOptions(),
                defaults.invocationOptions());
        AgentRunBudget budget = new AgentRunBudget(1000, 1000, 1000, 10, 10, 2, "USD", 1000);
        AgentRunLimits limits = new AgentRunLimits(10, 2, 1, 60_000, 30_000);
        ResolvedDefinition definition = new ResolvedDefinition(
                new AgentDefinitionId("external-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                Set.of(),
                Set.of(),
                "Complete the objective.");
        RuntimeCallerContext caller =
                new RuntimeCallerContext(new TenantRef("tenant"), new PrincipalRef("principal", "user"));
        ContentAddressedSnapshotFactory factory = new ContentAddressedSnapshotFactory();

        var first = factory.create(
                request("snapshot-one"),
                definition,
                new ResolvedProfile("profile", "1.0", AgentRunType.CHAT, budget, limits, defaults),
                caller);
        var second = factory.create(
                request("snapshot-one"),
                definition,
                new ResolvedProfile("profile", "1.0", AgentRunType.CHAT, budget, limits, alternative),
                caller);

        assertThat(first.reference()).isNotEqualTo(second.reference());
        assertThat(first.model().modelId().value()).isEqualTo("deepseek-v4-pro");
        assertThat(second.model().modelId().value()).isEqualTo("deepseek-v4-flash");
    }

    private static AgentRunRequest request(String key) {
        return new AgentRunRequest(
                key,
                new AgentDefinitionId("external-agent"),
                Optional.empty(),
                "default",
                new AgentSessionId("external-session"),
                Optional.empty(),
                "Use the echo tool, then finish.",
                List.of(),
                RuntimeOverrides.NONE);
    }
}
