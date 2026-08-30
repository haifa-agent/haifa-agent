package io.haifa.example.runtime.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.DeepSeekDefaults;
import io.haifa.agent.model.openai.OpenAiCompatibleChatModel;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.execution.LocalExecutionScheduler;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.example.runtime.scenario.RuntimeScenario;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared direct-Runtime assembly used by the capability scenarios.
 *
 * <p>This support class belongs to a non-published example module and is not an SDK API.
 */
public final class DeepSeekRuntimeRunner {
    private static final Duration COMPLETION_TIMEOUT = Duration.ofSeconds(180);

    private DeepSeekRuntimeRunner() {}

    public static void run(
            String apiKey,
            RuntimeScenario scenario,
            String objective,
            RuntimePersistencePorts persistence,
            TimeProvider time)
            throws Exception {
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("Agent objective must not be blank");
        }

        var provider = DeepSeekDefaults.provider();
        var modelDefinition = provider.models().stream()
                .filter(candidate -> candidate.id().equals(DeepSeekDefaults.MODEL_ID))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("DeepSeek V4 Pro model definition is unavailable"));
        var binding = provider.binding(modelDefinition.style());
        String adapterType = ModelApiStyles.adapterType(modelDefinition.style());
        ResolvedModelSnapshot modelSnapshot = ResolvedModelSnapshot.create(
                provider.id(),
                provider.version(),
                modelDefinition.id(),
                modelDefinition.version(),
                modelDefinition.providerModelId(),
                adapterType,
                "1.0.0",
                modelDefinition.style(),
                binding.dialect(),
                binding.resolveEndpoint(provider.endpoint()),
                provider.credentialRef(),
                provider.nativeStreaming(),
                modelDefinition.capabilities(),
                modelDefinition.contextWindow(),
                scenario.maxOutputTokens(),
                provider.options(),
                Map.of("thinking", "disabled"));
        var model = new OpenAiCompatibleChatModel(
                provider,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(20))
                        .build(),
                new ObjectMapper(),
                ignored -> new ResolvedCredential(apiKey));

        try (var scheduler = new LocalExecutionScheduler()) {
            var runtimeBuilder = new RuntimeCoreBuilder()
                    .timeProvider(time)
                    .persistence(persistence)
                    .scheduler(scheduler)
                    .registerChatModel(adapterType, "1.0.0", model)
                    .definitions((id, requestedVersion) -> new ResolvedDefinition(
                            id,
                            requestedVersion.orElse(new AgentDefinitionVersion(1, 0, 0)),
                            scenario.allowedToolAliases(),
                            scenario.allowedSkillAliases(),
                            java.util.Set.of(),
                            scenario.instructions(),
                            List.of()))
                    .profiles((profileId, overrides) -> profile(profileId, modelSnapshot, scenario));
            scenario.toolCatalog()
                    .ifPresent(catalog -> runtimeBuilder.toolPlatform(
                            catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator()));
            scenario.configure(runtimeBuilder);
            var runtime = runtimeBuilder.build();

            String executionId = UUID.randomUUID().toString();
            var accepted = runtime.start(new AgentRunRequest(
                    "deepseek-runtime-demo-" + executionId,
                    new AgentDefinitionId("deepseek-v4-pro-demo"),
                    Optional.of(new AgentDefinitionVersion(1, 0, 0)),
                    "deepseek-v4-pro-direct",
                    new AgentSessionId("deepseek-runtime-session-" + executionId),
                    Optional.empty(),
                    objective,
                    List.of(),
                    RuntimeOverrides.NONE));

            System.out.println("scenario = " + scenario.id());
            System.out.println("runId = " + accepted.runId().value());
            System.out.println("acceptedStatus = " + accepted.status());
            var completed = runtime.handle(accepted.runId())
                    .awaitCompletion(COMPLETION_TIMEOUT)
                    .orElseThrow(
                            () -> new IllegalStateException("DeepSeek Agent Run did not reach a terminal state within "
                                    + COMPLETION_TIMEOUT.toSeconds()
                                    + " seconds"));

            System.out.println("finalStatus = " + completed.status());
            if (completed.status() != AgentRunStatus.COMPLETED) {
                throw new IllegalStateException("DeepSeek Agent Run failed: "
                        + completed.error().map(Object::toString).orElse("no safe error"));
            }
            System.out.println("output = " + completed.output().orElse("<empty>"));
            System.out.println("usage = " + completed.usage());
        }
    }

    private static ResolvedProfile profile(
            String profileId, ResolvedModelSnapshot modelSnapshot, RuntimeScenario scenario) {
        boolean capabilityCalls = scenario.usesCapabilityCalls();
        return new ResolvedProfile(
                profileId,
                "1.0.0",
                AgentRunType.CHAT,
                new AgentRunBudget(
                        32_768,
                        modelSnapshot.maxOutputTokens(),
                        32_768,
                        capabilityCalls ? 1 : 0,
                        capabilityCalls ? 3 : 2,
                        0,
                        "USD",
                        100_000),
                new AgentRunLimits(
                        capabilityCalls ? 6 : 4,
                        0,
                        1,
                        COMPLETION_TIMEOUT.toMillis(),
                        120_000,
                        capabilityCalls ? 3 : 2,
                        capabilityCalls ? 1 : 0,
                        0),
                modelSnapshot);
    }
}
