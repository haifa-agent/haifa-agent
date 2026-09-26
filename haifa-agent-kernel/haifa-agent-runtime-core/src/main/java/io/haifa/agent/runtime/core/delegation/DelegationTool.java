package io.haifa.agent.runtime.core.delegation;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.runtime.core.bootstrap.DefinitionResolver;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The single Runtime-owned, model-visible delegation Tool.
 *
 * <p>It mirrors DeerFlow's {@code task}: one call delegates one objective to one allowed child agent, and the
 * call returns only after that child run is terminal. The Tool is disclosed only to runs whose frozen
 * configuration allows at least one child agent and whose depth still permits delegation, so a child run
 * never sees it (maximum delegation depth is one).
 */
public final class DelegationTool {
    public static final String NAME = "task";
    public static final String VERSION = "1.0.0";
    public static final String INPUT_SCHEMA_ID = "haifa.runtime.delegation.task.input";
    public static final String INPUT_SCHEMA_VERSION = "1";
    /** Delegation depth is intentionally fixed at one: only root runs may delegate. */
    public static final int MAX_DELEGATION_DEPTH = 1;

    static final int MAX_OBJECTIVE_LENGTH = 8_000;
    static final int MAX_CONTEXT_LENGTH = 32_000;
    static final int MAX_EXPECTED_OUTPUT_LENGTH = 4_000;
    private static final int MAX_DESCRIPTION_LENGTH = 1_000;

    private DelegationTool() {}

    /** True when the request targets the Runtime delegation Tool instead of a catalog Tool. */
    public static boolean isDelegation(ToolRequest request) {
        return NAME.equals(request.toolName())
                && INPUT_SCHEMA_ID.equals(request.arguments().schemaId());
    }

    /** True when the persisted Tool Call was produced by the Runtime delegation Tool. */
    public static boolean isDelegation(ToolCall call) {
        return NAME.equals(call.toolName())
                && INPUT_SCHEMA_ID.equals(call.arguments().schemaId());
    }

    /** True when the frozen configuration and depth of the run permit delegation at all. */
    public static boolean permitsDelegation(AgentRun run, RuntimeConfigurationSnapshot configuration) {
        return !configuration.allowedChildAgents().isEmpty()
                && run.depth() < MAX_DELEGATION_DEPTH
                && run.depth() < run.limits().maxDepth()
                && run.limits().maxChildRuns() > 0;
    }

    /**
     * Builds the model-visible specification listing each allowed child agent and its description, or empty
     * when the run must not delegate.
     */
    public static Optional<ModelToolSpecification> specification(
            AgentRun run, RuntimeConfigurationSnapshot configuration, DefinitionResolver definitions) {
        Objects.requireNonNull(run, "run must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(definitions, "definitions must not be null");
        if (!permitsDelegation(run, configuration)) return Optional.empty();
        List<String> agents = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        configuration.allowedChildAgents().stream()
                .map(AgentDefinitionId::value)
                .sorted()
                .forEach(id -> {
                    String description = describe(definitions, new AgentDefinitionId(id));
                    agents.add(id);
                    lines.add("- " + id + ": " + description);
                });
        if (agents.isEmpty()) return Optional.empty();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "agent",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.copyOf(agents),
                        "description",
                        "The child agent that performs the delegated objective."));
        properties.put(
                "objective",
                Map.of(
                        "type",
                        "string",
                        "maxLength",
                        MAX_OBJECTIVE_LENGTH,
                        "description",
                        "The complete, self-contained objective for the child agent."));
        properties.put(
                "context",
                Map.of(
                        "type",
                        "string",
                        "maxLength",
                        MAX_CONTEXT_LENGTH,
                        "description",
                        "Background, known facts, constraints and relevant references the child needs; "
                                + "the child does not see this conversation."));
        properties.put(
                "expected_output",
                Map.of(
                        "type",
                        "string",
                        "maxLength",
                        MAX_EXPECTED_OUTPUT_LENGTH,
                        "description",
                        "What the child should return, such as findings with evidence or a short summary."));
        Map<String, Object> schema = Map.of(
                "type",
                "object",
                "properties",
                Map.copyOf(properties),
                "required",
                List.of("agent", "objective"),
                "additionalProperties",
                false);
        String description = String.join(
                "\n",
                "Delegate one self-contained objective to a child agent that runs in its own isolated context. "
                        + "Several calls in the same response run in parallel. Each call returns after its child "
                        + "run finishes, with the child's final summary, status, usage and artifact references.",
                "Available child agents:",
                String.join("\n", lines));
        return Optional.of(new ModelToolSpecification(
                NAME, VERSION, description, INPUT_SCHEMA_ID, INPUT_SCHEMA_VERSION, schema, false));
    }

    private static String describe(DefinitionResolver definitions, AgentDefinitionId id) {
        String description;
        try {
            ResolvedDefinition resolved = definitions.resolve(id, Optional.empty());
            description = resolved.description().isBlank() ? id.value() : resolved.description();
        } catch (RuntimeException unavailable) {
            description = id.value();
        }
        String singleLine = description.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_DESCRIPTION_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_DESCRIPTION_LENGTH);
    }

    /**
     * Parses model-supplied arguments. Invalid arguments raise {@link IllegalArgumentException} with a safe,
     * model-repairable message; they never fail the parent run.
     */
    public static Arguments parse(Map<String, Object> values) {
        Objects.requireNonNull(values, "values must not be null");
        for (String key : values.keySet()) {
            if (!List.of("agent", "objective", "context", "expected_output").contains(key)) {
                throw new IllegalArgumentException("unknown argument '" + key + "'");
            }
        }
        String agent = text(values, "agent", 128, true);
        String objective = text(values, "objective", MAX_OBJECTIVE_LENGTH, true);
        String context = text(values, "context", MAX_CONTEXT_LENGTH, false);
        String expected = text(values, "expected_output", MAX_EXPECTED_OUTPUT_LENGTH, false);
        return new Arguments(new AgentDefinitionId(agent), objective, context, expected);
    }

    private static String text(Map<String, Object> values, String key, int maximumLength, boolean required) {
        Object value = values.get(key);
        if (value == null) {
            if (required) throw new IllegalArgumentException("argument '" + key + "' is required");
            return "";
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("argument '" + key + "' must be a string");
        }
        String normalized = text.trim();
        if (required && normalized.isEmpty()) {
            throw new IllegalArgumentException("argument '" + key + "' must not be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("argument '" + key + "' exceeds " + maximumLength + " characters");
        }
        return normalized;
    }

    /** Validated delegation arguments of one Tool Call. */
    public record Arguments(AgentDefinitionId agent, String objective, String context, String expectedOutput) {
        public Arguments {
            agent = Objects.requireNonNull(agent, "agent must not be null");
            objective = Objects.requireNonNull(objective, "objective must not be null");
            context = Objects.requireNonNull(context, "context must not be null");
            expectedOutput = Objects.requireNonNull(expectedOutput, "expectedOutput must not be null");
        }

        /** The first user message of the child session: objective plus the explicit brief from the parent. */
        public String brief() {
            List<String> sections = new ArrayList<>();
            sections.add(objective);
            if (!context.isEmpty()) sections.add("Context:\n" + context);
            if (!expectedOutput.isEmpty()) sections.add("Expected output:\n" + expectedOutput);
            return String.join("\n\n", sections);
        }
    }
}
