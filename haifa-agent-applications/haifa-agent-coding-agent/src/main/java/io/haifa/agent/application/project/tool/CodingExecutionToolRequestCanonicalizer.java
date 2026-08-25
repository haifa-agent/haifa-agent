package io.haifa.agent.application.project.tool;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.tool.ToolRequestCanonicalizer;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Canonicalizes Coding execution workdirs before any policy or approval digest is created. */
public final class CodingExecutionToolRequestCanonicalizer implements ToolRequestCanonicalizer {
    private static final String EXECUTION_RUN = "execution.run";
    private static final Set<String> EXECUTION_TOOLS =
            Set.of(EXECUTION_RUN, ProjectPermissionRequestOperations.TOOL_NAME);

    private final UnaryOperator<String> workspaceWorkdirCanonicalizer;

    public CodingExecutionToolRequestCanonicalizer(UnaryOperator<String> workspaceWorkdirCanonicalizer) {
        this.workspaceWorkdirCanonicalizer =
                Objects.requireNonNull(workspaceWorkdirCanonicalizer, "workspaceWorkdirCanonicalizer must not be null");
    }

    @Override
    public ToolRequest canonicalize(AgentRun run, FrozenToolBinding binding, ToolRequest request) {
        Objects.requireNonNull(run, "run must not be null");
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(request, "request must not be null");
        if (!EXECUTION_TOOLS.contains(binding.definition().name().value())) return request;

        Map<String, Object> values = request.arguments().values();
        Object rawWorkdir = values.getOrDefault("workdir", ".");
        if (!(rawWorkdir instanceof String workdir) || workdir.isBlank()) return request;

        String canonical = canonicalizeWorkdir(workdir, workspaceWorkdirCanonicalizer);
        if (values.containsKey("workdir") && canonical.equals(rawWorkdir)) return request;

        var canonicalValues = new LinkedHashMap<String, Object>(values);
        canonicalValues.put("workdir", canonical);
        ToolArguments arguments = new ToolArguments(
                request.arguments().schemaId(), request.arguments().schemaVersion(), canonicalValues);
        return new ToolRequest(
                request.toolCallId(),
                request.providerCorrelationId(),
                request.idempotencyKey(),
                request.toolName(),
                request.toolVersion(),
                arguments);
    }

    private static String logicalCanonical(String workdir) {
        try {
            return workdir.equals(".") ? "." : ProjectPath.of(workdir).toString();
        } catch (IllegalArgumentException ignored) {
            // Preserve invalid or out-of-workspace targets so the execution boundary rejects them fail-closed.
            return workdir;
        }
    }

    static String canonicalizeWorkdir(String workdir, UnaryOperator<String> workspaceCanonicalizer) {
        String workspaceCanonical = Objects.requireNonNull(
                Objects.requireNonNull(workspaceCanonicalizer, "workspaceCanonicalizer must not be null")
                        .apply(Objects.requireNonNull(workdir, "workdir must not be null")),
                "workspaceCanonicalizer must not return null");
        return logicalCanonical(workspaceCanonical);
    }
}
