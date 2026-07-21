package io.haifa.agent.application.project.tool;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.project.workspace.WorkspaceId;

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
}
