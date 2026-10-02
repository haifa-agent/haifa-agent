package io.haifa.example.sdk.advanced;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.policy.api.PolicyRuleSource;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgentBuilder;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import io.haifa.agent.starter.McpServerSpec;
import io.haifa.agent.starter.McpToolPlatforms;
import io.haifa.agent.store.sqlite.SqliteSdkContributions;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * External-package proof that declared MCP servers assemble into an arbitrary {@code HaifaAgentBuilder},
 * including the persistent SQLite assembly Super Harness uses. The client always runs through the SDK
 * production {@code SdkMcpClientFactory}; only the remote MCP server is a loopback test double.
 */
class McpPersistentBuilderAssemblyTest {
    private static final TenantRef TENANT = new TenantRef("external-tenant");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("external-principal", "user");
    private static final SecretKeySpec KEY =
            new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "AES");

    @Test
    void assemblesMcpPlatformIntoPersistentBuilder(@TempDir Path directory) throws Exception {
        try (var server = new LoopbackMcpServer("status-server", new String[] {"get_status"})) {
            var mcp = McpToolPlatforms.connect(
                    List.of(spec("status-server", server.endpoint(), "status")), TENANT, PRINCIPAL);
            AtomicReference<List<String>> disclosedTools = new AtomicReference<>();
            AgentChatModel model =
                    toolCallingModel(List.of(toolCall("call-status", "status_get_status", "status")), disclosedTools);

            AgentRunId runId;
            try (var agent = build(directory.resolve("assembly.sqlite"), model, mcp)) {
                runId = runOne(agent, "check status");

                assertThat(agent.runs().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(disclosedTools.get()).containsExactly("status_get_status");
                assertThat(server.calls()).containsExactly("status-server:get_status:status");
                assertThat(agent.runs().toolCalls(runId))
                        .extracting(ToolCallView::toolName)
                        .containsExactly("status_get_status");
            }

            awaitCondition(() -> server.deleteCount() >= 1);
            assertThat(server.deleteCount()).isGreaterThanOrEqualTo(1);
        }
    }

    private static HaifaAgent build(Path database, AgentChatModel model, McpToolPlatforms.McpToolPlatform mcp) {
        ResolvedModelSnapshot snapshot = snapshot();
        SqliteSdkContributions sqlite = SqliteSdkContributions.initializeWithKey(
                SqliteStoreConfiguration.defaults(database), Clock.systemUTC(), KEY);
        HaifaAgentBuilder builder = HaifaAgents.builder(profile(snapshot))
                .callerProvider(() -> new SdkCaller(TENANT, PRINCIPAL))
                .model(modelContribution(model, snapshot))
                .persistence(sqlite.persistence())
                .conversation(sqlite.conversation())
                .policy(allowAllPolicy());
        try {
            mcp.applyTo(builder);
            return builder.build();
        } catch (RuntimeException | Error failure) {
            mcp.close();
            sqlite.persistence().close();
            throw failure;
        }
    }

    private static AgentRunId runOne(HaifaAgent agent, String message) throws InterruptedException {
        var conversation = agent.conversations()
                .start(new StartConversationCommand("run-" + UUID.randomUUID(), "External MCP", message));
        agent.runs().await(conversation.runId());
        return conversation.runId();
    }

    private static AgentChatModel toolCallingModel(
            List<ModelToolCall> scriptedCalls, AtomicReference<List<String>> disclosedTools) {
        AtomicInteger step = new AtomicInteger();
        return request -> {
            disclosedTools.set(
                    request.tools().stream().map(tool -> tool.name()).sorted().toList());
            return respond(request, scriptedCalls, step.getAndIncrement());
        };
    }

    private static AgentChatResponse respond(AgentChatRequest request, List<ModelToolCall> scriptedCalls, int index) {
        if (index < scriptedCalls.size()) {
            return new AgentChatResponse(
                    "tool-" + index,
                    request.model().providerModelId(),
                    "",
                    List.of(scriptedCalls.get(index)),
                    ModelFinishReason.TOOL_CALLS,
                    ModelUsage.unpriced(4, 2),
                    "",
                    Map.of());
        }
        return new AgentChatResponse(
                "final",
                request.model().providerModelId(),
                "done",
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(5, 3),
                "",
                Map.of());
    }

    private static ModelToolCall toolCall(String correlationId, String toolName, String name) {
        return new ModelToolCall(new ProviderToolCallCorrelationId(correlationId), toolName, Map.of("name", name));
    }

    private static McpServerSpec spec(String name, URI endpoint, String prefix) {
        return McpServerSpec.streamableHttp(name, endpoint)
                .allowTools("get_status")
                .toolNamePrefix(prefix)
                .readOnly()
                .allowLoopbackHttp();
    }

    private static ResolvedModelSnapshot snapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("external"),
                "1.0.0",
                new ModelDefinitionId("external-mcp-model"),
                "1.0.0",
                "external-mcp-model",
                "external-adapter",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://EXTERNAL_MODEL_KEY"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }

    private static ModelContribution modelContribution(AgentChatModel model, ResolvedModelSnapshot snapshot) {
        return new ModelContribution(
                Map.of(ModelAdapterCoordinate.from(snapshot), model),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));
    }

    private static ProductProfile profile(ResolvedModelSnapshot snapshot) {
        return ProductProfile.create(
                new ProductId("external-mcp-persistent"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("external-mcp-persistent-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "Use the declared MCP tools when asked.",
                new ProductRunProfileRef(snapshot.modelId().value(), "1.0.0"),
                new AgentRunBudget(65_536, 8_192, 65_536, 16, 16, 0, "USD", 100),
                new AgentRunLimits(16, 0, 1, 120_000, 60_000, 16, 16, 0),
                Set.of(),
                Set.of());
    }

    private static PolicyPlatformContribution allowAllPolicy() {
        PolicyRule defaultRule = new PolicyRule(
                new PolicyRuleRef("external-mcp-default", "1"),
                PolicyRuleSource.MANAGED,
                0,
                PolicyRuleMatcher.any(),
                PolicyEffect.ALLOW,
                Optional.empty(),
                "EXTERNAL_MCP_DEFAULT_ALLOW",
                "Allowed by the external assembly test policy");
        return new PolicyPlatformContribution(
                PolicyRuleSet.of(List.of(), Optional.of(defaultRule), ApprovalMode.AUTO),
                new DefaultPolicyDecisionService());
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
