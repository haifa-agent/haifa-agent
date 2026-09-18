package io.haifa.example.runtime;

import io.haifa.agent.common.time.SystemTimeProvider;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.example.runtime.scenario.McpRuntimeScenario;
import io.haifa.example.runtime.scenario.ModelOnlyRuntimeScenario;
import io.haifa.example.runtime.scenario.RawToolRuntimeScenario;
import io.haifa.example.runtime.scenario.RuntimeScenario;
import io.haifa.example.runtime.scenario.SkillRuntimeScenario;
import io.haifa.example.runtime.support.DeepSeekRuntimeRunner;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Explicit, potentially billable DeepSeek runtime demo that selects one {@link RuntimeCoreBuilder}
 * capability scenario.
 */
public final class DeepSeekRuntimeDemo {
    static final String API_KEY_ENVIRONMENT_VARIABLE = "DEEPSEEK_API_KEY";
    static final String MCP_URL_ENVIRONMENT_VARIABLE = "HAIFA_UTILITY_MCP_URL";
    static final String WITH_TOOL_ARGUMENT = "--with-tool";
    static final String WITH_MCP_ARGUMENT = "--with-mcp";
    static final String WITH_SKILL_ARGUMENT = "--with-skill";
    private static final URI DEFAULT_MCP_ENDPOINT = URI.create("http://127.0.0.1:20002/mcp");

    private DeepSeekRuntimeDemo() {}

    public static void main(String[] arguments) throws Exception {
        RunOptions options = parseOptions(arguments);
        String apiKey =
                resolveApiKey(System.getenv(API_KEY_ENVIRONMENT_VARIABLE), DeepSeekRuntimeDemo::promptForApiKey);
        var time = new SystemTimeProvider();
        RuntimePersistencePorts persistence = RuntimePersistencePorts.inMemory();
        try (RuntimeScenario scenario =
                openScenario(options.scenario(), persistence, time, System.getenv(MCP_URL_ENVIRONMENT_VARIABLE))) {
            String objective = options.objective().orElseGet(scenario::defaultObjective);
            DeepSeekRuntimeRunner.run(apiKey, scenario, objective, persistence, time);
        }
    }

    static RunOptions parseOptions(String[] arguments) {
        ScenarioKind scenario = ScenarioKind.MODEL_ONLY;
        boolean capabilitySelected = false;
        List<String> objectiveParts = new ArrayList<>();
        for (String argument : arguments) {
            ScenarioKind selected =
                    switch (argument) {
                        case WITH_TOOL_ARGUMENT -> ScenarioKind.RAW_TOOL;
                        case WITH_MCP_ARGUMENT -> ScenarioKind.MCP;
                        case WITH_SKILL_ARGUMENT -> ScenarioKind.SKILL;
                        default -> null;
                    };
            if (selected != null) {
                if (capabilitySelected) {
                    throw new IllegalArgumentException(WITH_TOOL_ARGUMENT + ", " + WITH_MCP_ARGUMENT + ", and "
                            + WITH_SKILL_ARGUMENT + " are mutually exclusive");
                }
                scenario = selected;
                capabilitySelected = true;
            } else if (argument.startsWith("--")) {
                throw new IllegalArgumentException("Unknown option: " + argument);
            } else {
                objectiveParts.add(argument);
            }
        }

        Optional<String> objective = objectiveParts.isEmpty()
                ? Optional.empty()
                : Optional.of(String.join(" ", objectiveParts).trim());
        if (objective.isPresent() && objective.orElseThrow().isBlank()) {
            throw new IllegalArgumentException("Agent objective must not be blank");
        }
        return new RunOptions(scenario, objective);
    }

    static RuntimeScenario openScenario(
            ScenarioKind kind,
            RuntimePersistencePorts persistence,
            SystemTimeProvider time,
            String mcpEnvironmentValue) {
        return switch (kind) {
            case MODEL_ONLY -> new ModelOnlyRuntimeScenario();
            case RAW_TOOL -> new RawToolRuntimeScenario();
            case MCP -> McpRuntimeScenario.connect(resolveMcpEndpoint(mcpEnvironmentValue));
            case SKILL -> SkillRuntimeScenario.create(persistence, time);
        };
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

    enum ScenarioKind {
        MODEL_ONLY,
        RAW_TOOL,
        MCP,
        SKILL
    }

    @FunctionalInterface
    interface SecretPrompt {
        char[] read() throws IOException;
    }

    record RunOptions(ScenarioKind scenario, Optional<String> objective) {}
}
