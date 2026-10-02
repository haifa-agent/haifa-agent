package io.haifa.agent.sdk;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySubject;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgentBuilder;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.contribution.InMemoryConversationContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.sdk.internal.InMemoryPersistenceContribution;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class SdkTestFixtures {
    private SdkTestFixtures() {}

    public static ProductProfile profile(String productId) {
        return profile(productId, Set.of(), Set.of());
    }

    public static ProductProfile profile(String productId, Set<String> allowedTools, Set<String> allowedSkills) {
        return ProductProfile.create(
                new ProductId(productId),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId(productId + "-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "Answer the user carefully.",
                new ProductRunProfileRef(productId + "-chat", "1.0.0"),
                new AgentRunBudget(10_000, 10_000, 10_000, 8, 8, 0, "USD", 1_000),
                new AgentRunLimits(8, 0, 1, 30_000, 30_000),
                allowedTools,
                allowedSkills);
    }

    /** Builder with a Model, in-memory Persistence, and in-memory Conversation already assembled. */
    public static HaifaAgentBuilder builder(String productId) {
        return HaifaAgents.builder(profile(productId))
                .model(modelContribution())
                .persistence(persistenceContribution())
                .conversation(conversationContribution())
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()));
    }

    public static ModelContribution modelContribution() {
        ResolvedModelSnapshot snapshot = snapshot();
        AtomicInteger responses = new AtomicInteger();
        AgentChatModel model = request -> new AgentChatResponse(
                "response-" + responses.incrementAndGet(),
                "test-chat",
                "answer-" + responses.get(),
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
        return new ModelContribution(
                Map.of(ModelAdapterCoordinate.from(snapshot), model),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));
    }

    public static ModelContribution modelContribution(AgentChatModel model) {
        ResolvedModelSnapshot snapshot = snapshot();
        return new ModelContribution(
                Map.of(ModelAdapterCoordinate.from(snapshot), model),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));
    }

    public static AgentChatModel queueModel(AgentChatResponse... responses) {
        Queue<AgentChatResponse> queue = new ArrayDeque<>(List.of(responses));
        return request -> queue.remove();
    }

    public static AgentChatResponse toolCall(String name, Map<String, Object> arguments) {
        return new AgentChatResponse(
                "response-tool",
                "test-chat",
                "",
                List.of(new ModelToolCall(new ProviderToolCallCorrelationId("corr-" + name), name, arguments)),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    public static AgentChatResponse finalAnswer(String content) {
        return new AgentChatResponse(
                "response-final",
                "test-chat",
                content,
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    public static InteractionView awaitPending(HaifaAgent agent, AgentRunId runId) throws InterruptedException {
        for (int attempt = 0; attempt < 400; attempt++) {
            var pending = agent.runs().pendingInteraction(runId);
            if (pending.isPresent()) return pending.orElseThrow();
            if (agent.runs()
                    .find(runId)
                    .map(snapshot -> snapshot.status().isTerminal())
                    .orElse(false)) {
                throw new IllegalStateException("run terminated without an approval interaction");
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("no approval interaction appeared");
    }

    public static void approve(HaifaAgent agent, AgentRunId runId, InteractionView interaction) {
        agent.runs()
                .respond(new InteractionResponseSubmission(
                        new InteractionResponseId("approval-1"),
                        interaction.requestId(),
                        runId,
                        interaction.revision(),
                        InteractionAction.APPROVE,
                        List.of(),
                        "approval-key-1",
                        Instant.now()));
    }

    public static ResolvedModelSnapshot snapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("test"),
                "1.0",
                new ModelDefinitionId("test-chat"),
                "1.0",
                "test-chat",
                "test-adapter",
                "1.0",
                new ApiStyleId("test-style"),
                "standard",
                URI.create("https://model.invalid/v1"),
                new CredentialRef("credential:test"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }

    public static InMemoryPersistenceContribution persistenceContribution() {
        return new InMemoryPersistenceContribution();
    }

    public static InMemoryConversationContribution conversationContribution() {
        return new InMemoryConversationContribution();
    }

    public record FetchRequest(String value) {}

    public record FetchResponse(String value) {}

    public static final class FileWriteTool implements JavaTool<FetchRequest, FetchResponse> {
        private final AtomicInteger executions;

        public FileWriteTool(AtomicInteger executions) {
            this.executions = executions;
        }

        @Override
        public JavaToolSpec<FetchRequest, FetchResponse> spec() {
            return JavaToolSpec.builder("writer", FetchRequest.class, FetchResponse.class)
                    .description("Writes a file")
                    .sideEffects(ToolSideEffect.FILE_WRITE)
                    .build();
        }

        @Override
        public FetchResponse invoke(FetchRequest input, JavaToolContext context) {
            executions.incrementAndGet();
            return new FetchResponse(input.value());
        }
    }

    public static final class WebFetchTool implements JavaTool<FetchRequest, FetchResponse> {
        private final AtomicInteger executions;

        public WebFetchTool(AtomicInteger executions) {
            this.executions = executions;
        }

        @Override
        public JavaToolSpec<FetchRequest, FetchResponse> spec() {
            return JavaToolSpec.builder("web_fetch", FetchRequest.class, FetchResponse.class)
                    .description("Fetches a URL from an exact host")
                    .networkAccess("api.example.com")
                    .build();
        }

        @Override
        public FetchResponse invoke(FetchRequest input, JavaToolContext context) {
            executions.incrementAndGet();
            return new FetchResponse(input.value());
        }
    }

    public static final class MixedSideEffectTool implements JavaTool<FetchRequest, FetchResponse> {
        private final AtomicInteger executions;

        public MixedSideEffectTool(AtomicInteger executions) {
            this.executions = executions;
        }

        @Override
        public JavaToolSpec<FetchRequest, FetchResponse> spec() {
            return JavaToolSpec.builder("mixed", FetchRequest.class, FetchResponse.class)
                    .description("Reads a host and writes a file")
                    .networkAccess("api.example.com")
                    .sideEffects(ToolSideEffect.FILE_WRITE, ToolSideEffect.NETWORK_ACCESS)
                    .build();
        }

        @Override
        public FetchResponse invoke(FetchRequest input, JavaToolContext context) {
            executions.incrementAndGet();
            return new FetchResponse(input.value());
        }
    }

    public static ToolRegistration toolRegistration(
            String name,
            ToolRisk risk,
            ToolApprovalRequirement approval,
            Set<ToolSideEffect> effects,
            Set<String> networkHosts) {
        ToolProviderId providerId = new ToolProviderId("test." + name);
        ToolSchema schema = new ToolSchema(
                "test." + name + ".input",
                "1.0.0",
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", true));
        ToolDefinition definition = new ToolDefinition(
                new ToolName(name),
                new SemanticVersion("1.0.0"),
                providerId,
                name,
                name + " description",
                schema,
                schema,
                ToolExecutionMode.IN_PROCESS,
                true,
                Duration.ofSeconds(30),
                "per-run",
                ToolIdempotency.IDEMPOTENT,
                risk,
                effects,
                new ToolResourceRequirements(Set.of(), networkHosts, Set.of()),
                List.of(),
                approval,
                "test",
                false,
                Set.of());
        ToolProvider provider = new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return providerId;
            }

            @Override
            public io.haifa.agent.core.tool.ToolResult invoke(io.haifa.agent.tool.api.ToolInvocationRequest request) {
                return new io.haifa.agent.core.tool.ToolResult(true, "ok", Map.of(), List.of(), List.of(), false);
            }
        };
        return new ToolRegistration(new ToolAlias(name), definition, "test:" + name, provider);
    }

    public static ToolRegistration toolRegistration(
            String name, ToolRisk risk, ToolApprovalRequirement approval, Set<ToolSideEffect> effects) {
        return toolRegistration(name, risk, approval, effects, Set.of());
    }

    public static PolicyRequest policyRequest(
            String productId,
            String capability,
            String externalForm,
            PolicyRiskLevel riskLevel,
            Set<PolicySideEffect> effects,
            ApprovalMode mode) {
        return new PolicyRequest(
                new PolicySubject(new TenantRef("tenant"), new PrincipalRef("user", "user"), productId),
                new PolicyContext(
                        Optional.empty(),
                        Optional.of("session"),
                        Optional.of("run"),
                        Optional.empty(),
                        mode,
                        Optional.empty()),
                new PolicyAction(capability, "invoke"),
                new PolicyResource("tool", externalForm, Optional.of("digest"), "Tool"),
                new PolicyRisk(riskLevel, effects, false, Optional.of("api.example.com")));
    }
}
