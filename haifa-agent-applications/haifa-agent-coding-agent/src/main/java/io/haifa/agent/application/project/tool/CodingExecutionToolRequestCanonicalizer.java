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

/** Canonicalizes Coding workspace protocol fields before any policy or approval digest is created. */
public final class CodingExecutionToolRequestCanonicalizer implements ToolRequestCanonicalizer {
    private static final String EXECUTION_RUN = "execution.run";
    private static final Set<String> EXECUTION_TOOLS =
            Set.of(EXECUTION_RUN, ProjectPermissionRequestOperations.TOOL_NAME);

    public CodingExecutionToolRequestCanonicalizer() {}

    @Override
    public ToolRequest canonicalize(AgentRun run, FrozenToolBinding binding, ToolRequest request) {
        Objects.requireNonNull(run, "run must not be null");
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(request, "request must not be null");
        String toolName = binding.definition().name().value();
        if (!EXECUTION_TOOLS.contains(toolName) && !ProjectWorktreeToolOperations.TOOL_NAME.equals(toolName)) {
            return request;
        }

        Map<String, Object> values = request.arguments().values();
        var canonicalValues = new LinkedHashMap<String, Object>(values);
        if (ProjectWorktreeToolOperations.TOOL_NAME.equals(toolName)) {
            canonicalizeText(canonicalValues, "sourceWorkspaceRef");
            canonicalizeText(canonicalValues, "baseCommit");
            canonicalizeText(canonicalValues, "branchName");
            canonicalizeText(canonicalValues, "targetName");
            canonicalizeText(canonicalValues, "permission");
            canonicalizeText(canonicalValues, "deliveryIntent");
            return withArguments(request, values, canonicalValues);
        }

        canonicalizeText(canonicalValues, "workspaceRef");
        Object rawWorkdir = values.get("relativeWorkdir");
        if (rawWorkdir instanceof String workdir && !workdir.isBlank()) {
            canonicalValues.put("relativeWorkdir", canonicalizeRelativeWorkdir(workdir));
        }
        return withArguments(request, values, canonicalValues);
    }

    private static ToolRequest withArguments(
            ToolRequest request, Map<String, Object> original, Map<String, Object> canonical) {
        if (canonical.equals(original)) return request;
        ToolArguments arguments = new ToolArguments(
                request.arguments().schemaId(), request.arguments().schemaVersion(), canonical);
        return new ToolRequest(
                request.toolCallId(),
                request.providerCorrelationId(),
                request.idempotencyKey(),
                request.toolName(),
                request.toolVersion(),
                arguments);
    }

    private static void canonicalizeText(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value instanceof String text) values.put(field, text.trim());
    }

    static String canonicalizeRelativeWorkdir(String workdir) {
        try {
            return workdir.equals(".") ? "." : ProjectPath.of(workdir).toString();
        } catch (IllegalArgumentException ignored) {
            // Preserve invalid or out-of-workspace targets so the execution boundary rejects them fail-closed.
            return workdir;
        }
    }
}
