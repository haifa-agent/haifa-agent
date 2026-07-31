package io.haifa.agent.testing.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.common.time.SystemTimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolResult;
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
import io.haifa.agent.tool.core.DefaultToolCatalog;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Explicit, potentially billable DeepSeek probe that runs one Agent through {@link
 * RuntimeCoreBuilder}.
 */
public final class DeepSeekRuntimeMain {
    static final String API_KEY_ENVIRONMENT_VARIABLE = "DEEPSEEK_API_KEY";
    static final String MCP_URL_ENVIRONMENT_VARIABLE = "HAIFA_UTILITY_MCP_URL";
    static final String WITH_TOOL_ARGUMENT = "--with-tool";
    static final String WITH_MCP_ARGUMENT = "--with-mcp";
    static final String WITH_SKILL_ARGUMENT = "--with-skill";
    static final String ECHO_TOOL_ALIAS = "demo_echo";
    private static final String ECHO_TOOL_NAME = "demo.echo";
    private static final URI DEFAULT_MCP_ENDPOINT = URI.create("http://127.0.0.1:20002/mcp");
    private static final Duration COMPLETION_TIMEOUT = Duration.ofSeconds(180);
    private static final int MAX_OUTPUT_TOKENS = 256;
    private static final int SKILL_MAX_OUTPUT_TOKENS = 3_072;

    private DeepSeekRuntimeMain() {}

    public static void main(String[] arguments) throws Exception {
        RunOptions options = parseOptions(arguments);
        String apiKey =
                resolveApiKey(System.getenv(API_KEY_ENVIRONMENT_VARIABLE), DeepSeekRuntimeMain::promptForApiKey);

        var provider = DeepSeekDefaults.provider();
        int maxOutputTokens = options.skillEnabled() ? SKILL_MAX_OUTPUT_TOKENS : MAX_OUTPUT_TOKENS;
        var modelDefinition = provider.models().stream()
                .filter(candidate -> candidate.id().equals(DeepSeekDefaults.MODEL_ID))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("DeepSeek V4 Pro model definition is unavailable"));
        ResolvedModelSnapshot modelSnapshot = ResolvedModelSnapshot.create(
                provider.id(),
                provider.version(),
                modelDefinition.id(),
                modelDefinition.version(),
                modelDefinition.providerModelId(),
                provider.adapterType(),
                "1.0.0",
                provider.endpoint(),
                provider.credentialRef(),
                modelDefinition.capabilities(),
                modelDefinition.contextWindow(),
                maxOutputTokens,
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
        var time = new SystemTimeProvider();
        RuntimePersistencePorts persistence = RuntimePersistencePorts.inMemory();
        CounterfactualNewsroomSkillPlatform skillPlatform =
                options.skillEnabled() ? CounterfactualNewsroomSkillPlatform.create(persistence, time) : null;
        try (UtilityMcpRuntimePlatform mcpPlatform = options.mcpEnabled()
                        ? UtilityMcpRuntimePlatform.connect(
                                resolveMcpEndpoint(System.getenv(MCP_URL_ENVIRONMENT_VARIABLE)))
                        : null;
                var scheduler = new LocalExecutionScheduler()) {
            Optional<DefaultToolCatalog> toolCatalog = options.toolEnabled()
                    ? Optional.of(echoToolCatalog())
                    : options.mcpEnabled()
                            ? Optional.of(mcpPlatform.catalog())
                            : options.skillEnabled() ? Optional.of(skillPlatform.toolCatalog()) : Optional.empty();
            Set<String> allowedToolAliases = options.toolEnabled()
                    ? Set.of(ECHO_TOOL_ALIAS)
                    : options.mcpEnabled()
                            ? Set.of(UtilityMcpRuntimePlatform.LOCAL_TOOL_ALIAS)
                            : options.skillEnabled()
                                    ? Set.of(CounterfactualNewsroomSkillPlatform.SKILL_LOAD_ALIAS)
                                    : Set.of();
            var runtimeBuilder = new RuntimeCoreBuilder()
                    .timeProvider(time)
                    .persistence(persistence)
                    .scheduler(scheduler)
                    .registerChatModel(provider.adapterType(), "1.0.0", model)
                    .definitions((id, requestedVersion) -> new ResolvedDefinition(
                            id,
                            requestedVersion.orElse(new AgentDefinitionVersion(1, 0, 0)),
                            allowedToolAliases,
                            options.skillEnabled() ? Set.of(CounterfactualNewsroomSkillPlatform.SKILL_NAME) : Set.of(),
                            Set.of(),
                            options.toolEnabled()
                                    ? """
                                      The demo_echo tool is available.
                                      Call it only when requested by the objective.
                                      After receiving its result, answer directly and do not call it again.
                                      """
                                    : options.mcpEnabled()
                                            ? """
                                      The utility_unit_convert MCP tool is available.
                                      Call it exactly once when requested by the objective.
                                      After receiving its result, answer directly and do not call it again.
                                      """
                                            : options.skillEnabled()
                                                    ? """
                                      The run-counterfactual-newsrooms Skill is available as metadata.
                                      Before writing any news, call skill_load exactly once with
                                      skill set to run-counterfactual-newsrooms.
                                      On the next iteration, follow every activated Skill instruction.
                                      """
                                                    : """
                                      Answer the user's objective directly and concisely.
                                      Do not call tools.
                                      """,
                            List.of()))
                    .profiles((profileId, overrides) -> profile(
                            profileId,
                            modelSnapshot,
                            options.toolEnabled() || options.mcpEnabled() || options.skillEnabled()));
            toolCatalog.ifPresent(catalog -> runtimeBuilder.toolPlatform(
                    catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator()));
            if (skillPlatform != null) {
                runtimeBuilder.skillPlatform(skillPlatform.skillCatalog(), skillPlatform.contentLoader());
            }
            var runtime = runtimeBuilder.build();

            String executionId = UUID.randomUUID().toString();
            var accepted = runtime.start(new AgentRunRequest(
                    "deepseek-runtime-main-" + executionId,
                    new AgentDefinitionId("deepseek-v4-pro-main"),
                    Optional.of(new AgentDefinitionVersion(1, 0, 0)),
                    "deepseek-v4-pro-direct",
                    new AgentSessionId("deepseek-runtime-session-" + executionId),
                    Optional.empty(),
                    options.objective(),
                    List.of(),
                    RuntimeOverrides.NONE));

            System.out.println("toolEnabled = " + options.toolEnabled());
            System.out.println("mcpEnabled = " + options.mcpEnabled());
            System.out.println("skillEnabled = " + options.skillEnabled());
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

    static RunOptions parseOptions(String[] arguments) {
        boolean toolEnabled = false;
        boolean mcpEnabled = false;
        boolean skillEnabled = false;
        List<String> objectiveParts = new ArrayList<>();
        for (String argument : arguments) {
            if (WITH_TOOL_ARGUMENT.equals(argument)) {
                toolEnabled = true;
            } else if (WITH_MCP_ARGUMENT.equals(argument)) {
                mcpEnabled = true;
            } else if (WITH_SKILL_ARGUMENT.equals(argument)) {
                skillEnabled = true;
            } else if (argument.startsWith("--")) {
                throw new IllegalArgumentException("Unknown option: " + argument);
            } else {
                objectiveParts.add(argument);
            }
        }
        if ((toolEnabled ? 1 : 0) + (mcpEnabled ? 1 : 0) + (skillEnabled ? 1 : 0) > 1) {
            throw new IllegalArgumentException(WITH_TOOL_ARGUMENT + ", " + WITH_MCP_ARGUMENT + ", and "
                    + WITH_SKILL_ARGUMENT + " are mutually exclusive");
        }

        String objective;
        if (!objectiveParts.isEmpty()) {
            objective = String.join(" ", objectiveParts).trim();
        } else if (toolEnabled) {
            objective =
                    """
                    Call demo_echo exactly once with text runtime-tool-ok.
                    After receiving the tool result, reply exactly DEEPSEEK_V4_PRO_TOOL_OK.
                    """
                            .strip();
        } else if (mcpEnabled) {
            objective =
                    """
                    Call utility_unit_convert exactly once with value 1, fromUnit m, toUnit cm,
                    scale 2, and roundingMode HALF_UP.
                    After receiving the MCP result, reply exactly DEEPSEEK_V4_PRO_MCP_OK.
                    """
                            .strip();
        } else if (skillEnabled) {
            objective =
                    """
                    反事实前提：自 1995 年公共互联网商业化开始，底层协议强制所有数据在
                    30 天后永久失效，除非一名真实用户主动续期。

                    请运行平行世界新闻编辑部，出版 1996、2008、2025 三个年代的中文报纸版面，
                    然后审计三个年代之间的因果连续性，并指出最脆弱的因果环节。
                    """
                            .strip();
            objective += "\n这是紧凑演示：全文不超过 1200 个汉字。";
        } else {
            objective = "Reply with exactly DEEPSEEK_V4_PRO_RUNTIME_OK.";
        }
        if (objective.isBlank()) {
            throw new IllegalArgumentException("Agent objective must not be blank");
        }
        return new RunOptions(toolEnabled, mcpEnabled, skillEnabled, objective);
    }

    static URI resolveMcpEndpoint(String environmentValue) {
        if (environmentValue == null || environmentValue.isBlank()) {
            return DEFAULT_MCP_ENDPOINT;
        }
        return URI.create(environmentValue.trim());
    }

    static String resolveApiKey(String environmentValue, SecretPrompt prompt) throws IOException {
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }
        char[] entered = prompt.read();
        if (entered == null) {
            throw new IllegalStateException(API_KEY_ENVIRONMENT_VARIABLE + " is not set and no API key was entered");
        }
        try {
            String value = new String(entered).trim();
            if (value.isEmpty()) {
                throw new IllegalStateException(
                        API_KEY_ENVIRONMENT_VARIABLE + " is not set and the entered API key is blank");
            }
            return value;
        } finally {
            Arrays.fill(entered, '\0');
        }
    }

    static DefaultToolCatalog echoToolCatalog() {
        ToolProviderId providerId = new ToolProviderId("deepseek-runtime-main");
        ToolProvider provider = new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return providerId;
            }

            @Override
            public ToolResult invoke(io.haifa.agent.tool.api.ToolInvocationRequest request) {
                String text = String.valueOf(request.arguments().values().get("text"));
                return new ToolResult(true, "echoed: " + text, Map.of("text", text), List.of(), List.of(), false);
            }
        };
        Map<String, Object> inputSchema = Map.of(
                "$schema",
                ToolSchema.DRAFT_2020_12,
                "type",
                "object",
                "properties",
                Map.of("text", Map.of("type", "string", "minLength", 1, "maxLength", 128)),
                "required",
                List.of("text"),
                "additionalProperties",
                false);
        Map<String, Object> outputSchema = Map.of(
                "$schema",
                ToolSchema.DRAFT_2020_12,
                "type",
                "object",
                "properties",
                Map.of("text", Map.of("type", "string")),
                "required",
                List.of("text"),
                "additionalProperties",
                false);
        ToolDefinition definition = new ToolDefinition(
                new ToolName(ECHO_TOOL_NAME),
                new SemanticVersion("1.0.0"),
                providerId,
                "Demo Echo",
                "Returns the supplied text unchanged. Call at most once.",
                new ToolSchema("deepseek-runtime-main.echo.input", "1.0", inputSchema),
                new ToolSchema("deepseek-runtime-main.echo.output", "1.0", outputSchema),
                ToolExecutionMode.IN_PROCESS,
                true,
                Duration.ofSeconds(5),
                "single",
                ToolIdempotency.PURE,
                ToolRisk.LOW,
                Set.of(),
                ToolResourceRequirements.none(),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "haifa-agent-live-tests",
                false,
                Set.of("example", "live-test"));
        return new ToolCatalogBuilder()
                .register(new ToolAlias(ECHO_TOOL_ALIAS), definition, "deepseek-runtime-main.echo.v1", provider)
                .freeze();
    }

    private static ResolvedProfile profile(String profileId, ResolvedModelSnapshot modelSnapshot, boolean toolEnabled) {
        return new ResolvedProfile(
                profileId,
                "1.0.0",
                AgentRunType.CHAT,
                new AgentRunBudget(
                        32_768,
                        modelSnapshot.maxOutputTokens(),
                        32_768,
                        toolEnabled ? 1 : 0,
                        toolEnabled ? 3 : 2,
                        0,
                        "USD",
                        100_000),
                new AgentRunLimits(toolEnabled ? 6 : 4, 0, 1, COMPLETION_TIMEOUT.toMillis(), 120_000),
                modelSnapshot);
    }

    private static char[] promptForApiKey() throws IOException {
        Console console = System.console();
        if (console != null) {
            return console.readPassword("%s is not set. Enter DeepSeek API key: ", API_KEY_ENVIRONMENT_VARIABLE);
        }

        System.err.println(
                API_KEY_ENVIRONMENT_VARIABLE + " is not set. Console masking is unavailable; input will be visible.");
        System.err.print("Enter DeepSeek API key: ");
        String value = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        return value == null ? null : value.toCharArray();
    }

    @FunctionalInterface
    interface SecretPrompt {
        char[] read() throws IOException;
    }

    record RunOptions(boolean toolEnabled, boolean mcpEnabled, boolean skillEnabled, String objective) {}
}
