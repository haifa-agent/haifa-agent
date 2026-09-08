package io.haifa.agent.application.project.tool;

import io.haifa.agent.application.project.workspace.WorkspaceAccess;
import io.haifa.agent.application.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.application.project.workspace.WorkspaceAccessStore;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.sandbox.api.GitWorktreeIsolationProvider;
import io.haifa.agent.sandbox.api.GitWorktreeRequest;
import io.haifa.agent.sandbox.api.IsolatedWorkspace;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** CA-only controlled worktree creation path. Runtime owns approval; this class owns convergence. */
public final class ProjectWorktreeToolOperations {
    public static final String TOOL_NAME = "workspace.worktree.create";

    private final GitWorktreeIsolationProvider provider;
    private final AuthorizedWorkspaceProvisioning provisioning;
    private final IdentifierGenerator identifiers;
    private final WorkspaceAccessStore workspaceAccess;

    public ProjectWorktreeToolOperations(
            GitWorktreeIsolationProvider provider,
            AuthorizedWorkspaceProvisioning provisioning,
            IdentifierGenerator identifiers,
            WorkspaceAccessStore workspaceAccess) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.provisioning = Objects.requireNonNull(provisioning, "provisioning must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess must not be null");
    }

    public ToolResult execute(ToolInvocationRequest invocation, RunWorkspaceAccess access) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(access, "access must not be null");
        Map<String, Object> values = invocation.arguments().values();
        WorkspaceId parent = new WorkspaceId(text(values, "sourceWorkspaceRef"));
        String baseCommit = text(values, "baseCommit");
        String branchName = text(values, "branchName");
        String targetName = safeTargetName(text(values, "targetName"));
        String deliveryIntent = deliveryIntent(text(values, "deliveryIntent"));
        workspaceAccess.require(invocation.tenant(), invocation.principal(), parent, WorkspaceAccessMode.DEVELOP);
        provisioning.scope().resolveExecutionDirectory(parent, ".");
        String identity = PolicyDigest.sha256Fields(List.of(
                "coding-worktree-v1",
                parent.value(),
                baseCommit,
                branchName,
                targetName,
                invocation.toolCallId().value(),
                identifiers.nextValue()));
        WorkspaceId child = new WorkspaceId("workspace-worktree-" + identity.substring(0, 32));
        WorkspaceBindingId binding = new WorkspaceBindingId("binding-worktree-" + identity.substring(0, 32));
        WorkspaceLocationRef location = new WorkspaceLocationRef("location-worktree-" + identity.substring(0, 32));
        IsolatedWorkspace isolated = null;
        try {
            invocation.observer().dispatched();
            isolated = provider.createWorktree(new GitWorktreeRequest(
                    parent,
                    child,
                    binding,
                    location,
                    invocation.principal(),
                    baseCommit,
                    branchName,
                    WorkspaceCapabilitySet.executionFiles(),
                    WorkspacePermissionSet.readWriteExecute()));
            var registered = provisioning.authorizeApprovedWorktree(
                    isolated.parentWorkspaceId(),
                    isolated.childWorkspaceId(),
                    isolated.bindingId(),
                    isolated.locationRef(),
                    targetName);
            workspaceAccess.replace(new WorkspaceAccess(
                    invocation.tenant(),
                    invocation.principal(),
                    registered.directory().workspaceId(),
                    WorkspaceAccessMode.DEVELOP));
            invocation.observer().acknowledged();
            var view = registered.registryView();
            return new ToolResult(
                    true,
                    "Created controlled worktree " + view.safeDisplayName(),
                    Map.of(
                            "workspaceRef", view.workspaceRef(),
                            "safeDisplayName", view.safeDisplayName(),
                            "sourceWorkspaceRef", parent.value(),
                            "baseCommit", baseCommit,
                            "branchName", branchName,
                            "mode", WorkspaceAccessMode.DEVELOP.name(),
                            "deliveryIntent", deliveryIntent,
                            "source", view.source().name(),
                            "status", view.status().name()),
                    List.of(),
                    List.of(),
                    false);
        } catch (RuntimeException failure) {
            if (isolated != null) {
                try {
                    workspaceAccess.delete(invocation.tenant(), invocation.principal(), isolated.childWorkspaceId());
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                try {
                    provisioning.revoke(isolated.childWorkspaceId(), "WORKTREE_CREATE_COMPENSATED");
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                try {
                    provider.releaseWorktree(isolated.childWorkspaceId(), true);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            return new ToolResult(
                    false,
                    "Controlled worktree creation failed; no workspace root was activated.",
                    Map.of(
                            "status", "FAILED",
                            "stableFailureCode", "WORKTREE_CREATE_FAILED",
                            "failureCategory", "LOCAL_ENVIRONMENT_UNAVAILABLE",
                            "resourceClass", "WORKSPACE",
                            "retryable", false),
                    List.of(),
                    List.of(),
                    false);
        }
    }

    private static String deliveryIntent(String value) {
        if (!value.equals("local-change") && !value.equals("pull-request")) {
            throw new IllegalArgumentException("deliveryIntent must be local-change or pull-request");
        }
        return value;
    }

    private static String safeTargetName(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) {
            throw new IllegalArgumentException("targetName must be a safe managed worktree name");
        }
        return value;
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be non-empty text");
        }
        return text.trim();
    }
}
