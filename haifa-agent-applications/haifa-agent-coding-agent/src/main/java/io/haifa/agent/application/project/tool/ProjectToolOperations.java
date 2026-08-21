package io.haifa.agent.application.project.tool;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.tool.api.ToolReconciliation;

/** Domain operation adapter; Runtime remains responsible for registry, schema, policy, approval, journal and retry. */
@FunctionalInterface
public interface ProjectToolOperations {
    ToolResult execute(
            String toolName,
            WorkspaceId workspaceId,
            PrincipalRef actor,
            String runRef,
            String policyDecisionRef,
            ToolArguments arguments);

    default ToolResult execute(
            String toolName,
            WorkspaceId workspaceId,
            PrincipalRef actor,
            String runRef,
            String toolCallRef,
            String idempotencyKey,
            String policyDecisionRef,
            ToolArguments arguments) {
        return execute(toolName, workspaceId, actor, runRef, policyDecisionRef, arguments);
    }

    default ToolReconciliation reconcile(
            String toolName,
            WorkspaceId workspaceId,
            PrincipalRef actor,
            String runRef,
            String toolCallRef,
            String idempotencyKey,
            ToolArguments arguments) {
        return ToolReconciliation.unsupported();
    }
}
