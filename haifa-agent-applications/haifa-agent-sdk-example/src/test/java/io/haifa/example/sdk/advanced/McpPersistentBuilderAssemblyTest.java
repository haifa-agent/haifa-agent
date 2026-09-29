package io.haifa.example.sdk.advanced;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.haifa.agent.model.api.ModelMessage;
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
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.conversation.ConversationQuery;
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
import java.util.function.Supplier;
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
    void assemblesTwoServersExposingTheSameToolNameWithoutMixingProvenance(@TempDir Path directory) throws Exception {
        try (var alpha = new LoopbackMcpServer("alpha-status", new String[] {"get_status"}, false, false);
                var beta = new LoopbackMcpServer("beta-status", new String[] {"get_status"}, false, false)) {
            var mcp = McpToolPlatforms.connect(
                    List.of(
                            spec("alpha-status", alpha.endpoint(), "alpha"),
                            spec("beta-status", beta.endpoint(), "beta")),
                    TENANT,
                    PRINCIPAL);
            AtomicReference<List<String>> disclosedTools = new AtomicReference<>();
            AtomicReference<List<ModelMessage>> lastMessages = new AtomicReference<>();
            AgentChatModel model = toolCallingModel(
                    List.of(
                            toolCall("call-alpha", "alpha_get_status", "alpha"),
                            toolCall("call-beta", "beta_get_status", "beta")),
                    disclosedTools,
                    lastMessages);

            try (var agent = build(directory.resolve("alpha-beta.sqlite"), model, mcp)) {
                AgentRunId runId = runOne(agent, "status of both servers");

                assertThat(agent.runs().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(disclosedTools.get()).containsExactly("alpha_get_status", "beta_get_status");
                assertThat(alpha.calls()).containsExactly("alpha-status:get_status:alpha");
                assertThat(beta.calls()).containsExactly("beta-status:get_status:beta");
                assertThat(agent.runs().toolCalls(runId))
                        .extracting(ToolCallView::toolName)
                        .containsExactlyInAnyOrder("alpha_get_status", "beta_get_status");
            }
        }
    }

    @Test
    void optionalServerFailureContributesNothingAndDoesNotPolluteTheOtherServer(@TempDir Path directory)
            throws Exception {
        try (var healthy = new LoopbackMcpServer("healthy-status", new String[] {"get_status"}, false, false);
                var broken = new LoopbackMcpServer("broken-status", new String[] {"get_status"}, true, false)) {
            var mcp = McpToolPlatforms.connect(
                    List.of(
                            spec("healthy-status", healthy.endpoint(), "healthy"),
                            spec("broken-status", broken.endpoint(), "broken").optional()),
                    TENANT,
                    PRINCIPAL);
            AtomicReference<List<String>> disclosedTools = new AtomicReference<>();
            AtomicReference<List<ModelMessage>> lastMessages = new AtomicReference<>();
            AgentChatModel model = toolCallingModel(
                    List.of(toolCall("call-healthy", "healthy_get_status", "healthy")), disclosedTools, lastMessages);

            try (var agent = build(directory.resolve("optional.sqlite"), model, mcp)) {
                AgentRunId runId = runOne(agent, "status");

                assertThat(agent.runs().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(disclosedTools.get()).containsExactly("healthy_get_status");
                assertThat(healthy.calls()).containsExactly("healthy-status:get_status:healthy");
                assertThat(broken.initializeCount()).isGreaterThanOrEqualTo(1);
                assertThat(agent.diagnostics())
                        .extracting(diagnostic -> diagnostic.code())
                        .contains("MCP_SERVER_UNAVAILABLE");
            }
        }
    }

    @Test
    void requiredServerFailureClosesOpenedResourcesAndFailsTheConnect() throws Exception {
        try (var healthy = new LoopbackMcpServer("healthy-status", new String[] {"get_status"}, false, false);
                var broken = new LoopbackMcpServer("broken-status", new String[] {"get_status"}, true, false)) {
            assertThatThrownBy(() -> McpToolPlatforms.connect(
                            List.of(
                                    spec("healthy-status", healthy.endpoint(), "healthy"),
                                    spec("broken-status", broken.endpoint(), "broken")
                                            .required()),
                            TENANT,
                            PRINCIPAL))
                    .isInstanceOf(HaifaAgentException.class)
                    .extracting("code")
                    .isEqualTo("MCP_SERVER_UNAVAILABLE");

            awaitCondition(() -> healthy.deleteCount() >= 1);
            assertThat(healthy.deleteCount()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void emptyServerListAddsNoToolAndLeavesTheAgentUsable(@TempDir Path directory) throws Exception {
        var mcp = McpToolPlatforms.connect(List.of(), TENANT, PRINCIPAL);
        AtomicReference<List<String>> disclosedTools = new AtomicReference<>();
        AtomicReference<List<ModelMessage>> lastMessages = new AtomicReference<>();
        AgentChatModel model = toolCallingModel(List.of(), disclosedTools, lastMessages);

        try (var agent = build(directory.resolve("empty.sqlite"), model, mcp)) {
            AgentRunId runId = runOne(agent, "no tools");

            assertThat(agent.runs().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(disclosedTools.get()).isEmpty();
            assertThat(agent.diagnostics()).isEmpty();
        }
    }

    @Test
    void injectsDeclaredCredentialAndRedactsItFromTheToolResult(@TempDir Path directory) throws Exception {
        String secret = "external-secret-token";
        try (var secure = new LoopbackMcpServer("secure-status", new String[] {"get_status"}, false, true)) {
            var mcp = McpToolPlatforms.connect(
                    List.of(bearerSpec("secure-status", secure.endpoint(), "secure", () -> secret)), TENANT, PRINCIPAL);
            AtomicReference<List<String>> disclosedTools = new AtomicReference<>();
            AtomicReference<List<ModelMessage>> lastMessages = new AtomicReference<>();
            AgentChatModel model = toolCallingModel(
                    List.of(toolCall("call-secure", "secure_get_status", "secure")), disclosedTools, lastMessages);

            try (var agent = build(directory.resolve("credential.sqlite"), model, mcp)) {
                AgentRunId runId = runOne(agent, "status");

                assertThat(agent.runs().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(secure.authorizationValues()).contains("Bearer " + secret);
                assertThat(renderText(lastMessages.get()))
                        .contains("[REDACTED]")
                        .doesNotContain(secret);
                assertThat(agent.diagnostics().toString()).doesNotContain(secret);
            }
            assertThat(secure.authorizationValues())
                    .allSatisfy(value -> assertThat(value).contains("Bearer"));
        }
    }

    @Test
    void closingTheAgentReleasesEveryMcpConnection(@TempDir Path directory) throws Exception {
        try (var alpha = new LoopbackMcpServer("alpha-status", new String[] {"get_status"}, false, false);
                var beta = new LoopbackMcpServer("beta-status", new String[] {"get_status"}, false, false)) {
            var mcp = McpToolPlatforms.connect(
                    List.of(
                            spec("alpha-status", alpha.endpoint(), "alpha"),
                            spec("beta-status", beta.endpoint(), "beta")),
                    TENANT,
                    PRINCIPAL);
            AtomicReference<List<String>> disclosedTools = new AtomicReference<>();
            AtomicReference<List<ModelMessage>> lastMessages = new AtomicReference<>();
            AgentChatModel model = toolCallingModel(List.of(), disclosedTools, lastMessages);

            HaifaAgent agent = build(directory.resolve("close.sqlite"), model, mcp);
            assertThat(alpha.deleteCount()).isZero();
            assertThat(beta.deleteCount()).isZero();

            agent.close();
            agent.close();

            awaitCondition(() -> alpha.deleteCount() >= 1 && beta.deleteCount() >= 1);
            assertThat(alpha.deleteCount()).isGreaterThanOrEqualTo(1);
            assertThat(beta.deleteCount()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void persistsRunAndToolFactsAcrossAgentRestart(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("restart.sqlite");
        AgentRunId runId;
        String sessionId;
        try (var server = new LoopbackMcpServer("persist-status", new String[] {"get_status"}, false, false)) {
            McpServerSpec spec = spec("persist-status", server.endpoint(), "persist");
            AtomicReference<List<String>> disclosedTools = new AtomicReference<>();
            AtomicReference<List<ModelMessage>> lastMessages = new AtomicReference<>();
            AgentChatModel model = toolCallingModel(
                    List.of(toolCall("call-persist", "persist_get_status", "persist")), disclosedTools, lastMessages);

            try (var agent = build(database, model, McpToolPlatforms.connect(List.of(spec), TENANT, PRINCIPAL))) {
                var conversation = agent.conversations()
                        .start(new StartConversationCommand(
                                "restart-" + UUID.randomUUID(), "Persistent MCP", "status"));
                runId = conversation.runId();
                sessionId = conversation.record().sessionId().value();
                assertThat(agent.runs().await(runId).status()).isEqualTo(AgentRunStatus.COMPLETED);
            }

            AtomicReference<List<ModelMessage>> reopenedMessages = new AtomicReference<>();
            AgentChatModel reopenedModel = toolCallingModel(List.of(), new AtomicReference<>(), reopenedMessages);
            try (var reopened =
                    build(database, reopenedModel, McpToolPlatforms.connect(List.of(spec), TENANT, PRINCIPAL))) {
                assertThat(reopened.runs().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(reopened.runs().toolCalls(runId))
                        .extracting(ToolCallView::toolName)
                        .contains("persist_get_status");
                assertThat(reopened.conversations()
                                .list(ConversationQuery.active(10))
                                .items())
                        .extracting(record -> record.sessionId().value())
                        .contains(sessionId);
            }
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
            List<ModelToolCall> scriptedCalls,
            AtomicReference<List<String>> disclosedTools,
            AtomicReference<List<ModelMessage>> lastMessages) {
        AtomicInteger step = new AtomicInteger();
        return request -> {
            disclosedTools.set(
                    request.tools().stream().map(tool -> tool.name()).sorted().toList());
            lastMessages.set(request.messages());
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

    private static String renderText(List<ModelMessage> messages) {
        if (messages == null) return "";
        StringBuilder rendered = new StringBuilder();
        for (ModelMessage message : messages) {
            rendered.append(message.content()).append('\n');
            rendered.append(message.toolResultData()).append('\n');
        }
        return rendered.toString();
    }

    private static McpServerSpec spec(String name, URI endpoint, String prefix) {
        return McpServerSpec.streamableHttp(name, endpoint)
                .allowTools("get_status")
                .toolNamePrefix(prefix)
                .readOnly()
                .allowLoopbackHttp();
    }

    private static McpServerSpec bearerSpec(String name, URI endpoint, String prefix, Supplier<String> token) {
        return McpServerSpec.streamableHttp(name, endpoint)
                .allowTools("get_status")
                .toolNamePrefix(prefix)
                .bearerToken(token)
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
