package io.haifa.agent.application.project.tool;

import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Executes one exact command after Runtime approval and verification of a prior eligible denial. */
public final class ProjectPermissionRequestOperations {
    public static final String TOOL_NAME = "execution.request_permissions";
    public static final String MODEL_ALIAS = "request_permissions";
    public static final String HOST_NETWORK_ACCESS = "HOST_NETWORK_ACCESS";
    private static final Set<String> ELIGIBLE_FAILURE_CODES = Set.of(
            "NETWORK_UNAVAILABLE",
            "NETWORK_PERMISSION_REQUIRED",
            "HOST_AUTHENTICATION_UNAVAILABLE",
            "GIT_AUTHENTICATION_UNAVAILABLE",
            "GH_AUTHENTICATION_UNAVAILABLE");
    private static final Set<SystemGitCliCommandClassifier.Risk> ELIGIBLE_RISKS = Set.of(
            SystemGitCliCommandClassifier.Risk.LOCAL_READ,
            SystemGitCliCommandClassifier.Risk.LOCAL_WRITE,
            SystemGitCliCommandClassifier.Risk.NETWORK_READ,
            SystemGitCliCommandClassifier.Risk.EXTERNAL_WRITE);

    private final RuntimeStateRepository state;
    private final ProjectExecutionToolOperations elevatedExecution;
    private final SandboxProfile defaultProfile;
    private final SandboxProfile elevatedProfile;

    public ProjectPermissionRequestOperations(
            RuntimeStateRepository state,
            ProjectExecutionToolOperations elevatedExecution,
            SandboxProfile defaultProfile,
            SandboxProfile elevatedProfile) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.elevatedExecution = Objects.requireNonNull(elevatedExecution, "elevatedExecution must not be null");
        this.defaultProfile = Objects.requireNonNull(defaultProfile, "defaultProfile must not be null");
        this.elevatedProfile = Objects.requireNonNull(elevatedProfile, "elevatedProfile must not be null");
    }

    public ToolResult execute(ToolInvocationRequest invocation, RunWorkspaceAccess access) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(access, "access must not be null");
        Map<String, Object> arguments = invocation.arguments().values();
        String priorToolCallId = requiredText(arguments, "priorToolCallId");
        String requestedPermission = requiredText(arguments, "requestedPermission");
        if (!HOST_NETWORK_ACCESS.equals(requestedPermission)) {
            return rejected("PERMISSION_KIND_UNSUPPORTED", "Only HOST_NETWORK_ACCESS can be requested.");
        }
        if (defaultProfile.equals(elevatedProfile)
                || elevatedProfile.networkPolicy() != NetworkPolicy.ALLOW
                || !"host-guarded".equals(elevatedProfile.providerId())) {
            return rejected(
                    "PERMISSION_REQUEST_NOT_ELIGIBLE",
                    "The trusted host has not configured a distinct approved host-network fallback profile.");
        }
        ToolCall prior = state.toolCalls(invocation.runId()).stream()
                .filter(call -> call.id().value().equals(priorToolCallId))
                .findFirst()
                .orElse(null);
        if (prior == null || prior.status() != ToolCallStatus.FAILED || !"execution.run".equals(prior.toolName())) {
            return rejected(
                    "PERMISSION_REQUEST_NOT_ELIGIBLE",
                    "The request must reference a failed execution.run Tool Call from the same Run.");
        }
        boolean alreadyUsed = state.toolCalls(invocation.runId()).stream()
                .filter(call -> !call.id().equals(invocation.toolCallId()))
                .filter(call -> TOOL_NAME.equals(call.toolName()))
                .filter(call -> call.status() == ToolCallStatus.COMPLETED || call.status() == ToolCallStatus.FAILED)
                .anyMatch(
                        call -> priorToolCallId.equals(call.arguments().values().get("priorToolCallId")));
        if (alreadyUsed) {
            return rejected(
                    "PERMISSION_REQUEST_ALREADY_USED",
                    "This failed Tool Call has already consumed its one controlled permission attempt.");
        }
        String failureCode = failureCode(prior);
        if (!ELIGIBLE_FAILURE_CODES.contains(failureCode)) {
            return rejected(
                    "PERMISSION_REQUEST_NOT_ELIGIBLE",
                    "The prior failure is not eligible for permission escalation: " + failureCode + ".");
        }
        if (!same(prior.arguments().values(), arguments, "command", "workdir", "timeoutMillis")
                || !ProjectExecutionToolOperations.expectedExitCodes(
                                prior.arguments().values())
                        .equals(ProjectExecutionToolOperations.expectedExitCodes(arguments))) {
            return rejected(
                    "PERMISSION_REQUEST_INTENT_MISMATCH",
                    "The command, workdir, timeout, and expected exit codes must match the failed Tool Call exactly.");
        }
        var classification = SystemGitCliCommandClassifier.classify(requiredText(arguments, "command"));
        if (classification.target() == SystemGitCliCommandClassifier.Target.OTHER
                || !ELIGIBLE_RISKS.contains(classification.risk())) {
            return rejected(
                    "PERMISSION_REQUEST_NOT_ELIGIBLE",
                    "Only direct, non-destructive system git or gh commands can request elevated execution.");
        }
        ToolResult result = elevatedExecution.execute(invocation, access);
        var data = new LinkedHashMap<String, Object>(result.structuredData());
        data.put("permissionEscalated", true);
        data.put("requestedPermission", HOST_NETWORK_ACCESS);
        data.put("priorToolCallId", priorToolCallId);
        return new ToolResult(
                result.successful(),
                result.summary(),
                Map.copyOf(data),
                result.assets(),
                result.artifacts(),
                result.truncated());
    }

    private static String failureCode(ToolCall call) {
        Map<String, Object> details = call.error().orElseThrow().error().details();
        for (String key : List.of("stableFailureCode", "failureCode")) {
            Object value = details.get(key);
            if (value instanceof String code && !code.isBlank()) return code;
        }
        return "UNKNOWN_FAILURE";
    }

    private static boolean same(Map<String, Object> prior, Map<String, Object> current, String... keys) {
        for (String key : keys) {
            Object left = key.equals("workdir") ? prior.getOrDefault(key, ".") : prior.get(key);
            Object right = key.equals("workdir") ? current.getOrDefault(key, ".") : current.get(key);
            if (!Objects.equals(left, right)) return false;
        }
        return true;
    }

    private static ToolResult rejected(String stableFailureCode, String summary) {
        return new ToolResult(
                false,
                summary,
                Map.of(
                        "status",
                        "FAILED",
                        "failureCategory",
                        "POLICY",
                        "stableFailureCode",
                        stableFailureCode,
                        "resourceClass",
                        "PERMISSION"),
                List.of(),
                List.of(),
                false);
    }

    private static String requiredText(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be non-empty text");
        }
        return text;
    }
}
