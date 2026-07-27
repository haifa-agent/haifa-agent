package io.haifa.agent.cli;

import io.haifa.agent.application.project.policy.CodingAgentPolicyAssembly;
import io.haifa.agent.application.project.product.coding.CodingSessionService;
import io.haifa.agent.application.project.product.coding.CodingShellPlan;
import io.haifa.agent.application.project.product.coding.CodingShellResult;
import io.haifa.agent.application.project.product.coding.CodingShellService;
import io.haifa.agent.application.project.tool.ProjectExecutionToolOperations;
import io.haifa.agent.application.project.tool.RunWorkspaceAccess;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionStatus;
import io.haifa.agent.policy.api.ApprovalRequester;
import io.haifa.agent.policy.api.ApprovalResponder;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyAuthorizationEvidence;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySubject;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Terminal shell orchestration; no ProcessBuilder/JLine builtin path exists here. */
final class CliCodingShellService implements CodingShellService {
    private final CodingSessionService sessions;
    private final ProjectExecutionToolOperations operations;
    private final CodingAgentPolicyAssembly policy;
    private final RuntimePersistencePorts persistence;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final TenantRef tenant;
    private final PrincipalRef principal;
    private final ProjectId projectId;
    private final WorkspaceId workspaceId;
    private final Duration timeout;
    private final String profileDigest;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    CliCodingShellService(
            CodingSessionService sessions,
            ProjectExecutionToolOperations operations,
            CodingAgentPolicyAssembly policy,
            RuntimePersistencePorts persistence,
            IdentifierGenerator ids,
            TimeProvider time,
            TenantRef tenant,
            PrincipalRef principal,
            ProjectId projectId,
            WorkspaceId workspaceId,
            Duration timeout,
            String profileDigest) {
        this.sessions = sessions;
        this.operations = operations;
        this.policy = policy;
        this.persistence = persistence;
        this.ids = ids;
        this.time = time;
        this.tenant = tenant;
        this.principal = principal;
        this.projectId = projectId;
        this.workspaceId = workspaceId;
        this.timeout = timeout;
        this.profileDigest = profileDigest;
    }

    @Override
    public CodingShellPlan plan(AgentSessionId sessionId, String command, boolean includeInContext) {
        var view = sessions.openSession(sessionId);
        if (!view.summary().projectId().equals(projectId)) {
            throw new IllegalStateException("Session is unavailable");
        }
        if (view.summary().status() != AgentSessionStatus.ACTIVE) {
            throw new IllegalStateException("SESSION_NOT_ACTIVE");
        }
        if (view.activeRun().isPresent()) {
            throw new IllegalStateException("CODING_SESSION_ACTIVE");
        }
        String safeCommand = command(command);
        rejectUnsupportedMode(safeCommand);
        String token = ids.nextValue();
        AgentRunId auditRunId = new AgentRunId("terminal-" + token);
        String resourceDigest = CliExecutionPlatform.policyResourceDigest(safeCommand, ".", profileDigest);
        PolicyRequest request = new PolicyRequest(
                new PolicySubject(tenant, principal, "haifa-coding-agent"),
                new PolicyContext(
                        Optional.of(projectId.value()),
                        Optional.of(sessionId.value()),
                        Optional.of(auditRunId.value()),
                        Optional.empty(),
                        policy.snapshot().approvalMode(),
                        Optional.empty(),
                        Optional.of(profileDigest)),
                new PolicyAction("execution.run", "invoke"),
                new PolicyResource(
                        "execution",
                        "terminal-shell-" + token,
                        Optional.of(resourceDigest),
                        "User-initiated terminal shell command"),
                new PolicyRisk(
                        PolicyRiskLevel.HIGH,
                        Set.of(PolicySideEffect.PROCESS_EXECUTION, PolicySideEffect.NETWORK_ACCESS),
                        false,
                        Optional.empty()));
        var decision = policy.decisions().evaluate(request, policy.snapshot());
        policy.decisionsStore().save(decision);
        Pending value = new Pending(
                token,
                sessionId,
                auditRunId,
                safeCommand,
                includeInContext,
                decision.id().value(),
                decision);
        pending.put(token, value);
        CodingShellPlan.State state =
                switch (decision.effect()) {
                    case ALLOW -> CodingShellPlan.State.READY;
                    case ASK -> CodingShellPlan.State.APPROVAL_REQUIRED;
                    case DENY -> CodingShellPlan.State.DENIED;
                };
        return new CodingShellPlan(token, sessionId, safeCommand, includeInContext, state, decision.reasonCode());
    }

    @Override
    public CodingShellResult execute(String token, boolean approved) {
        Pending value = Optional.ofNullable(pending.remove(token))
                .orElseThrow(() -> new IllegalStateException("Shell request is unavailable"));
        if (value.decision().effect() == PolicyEffect.DENY) {
            throw new SecurityException("POLICY_DENIED");
        }
        if (value.decision().effect() == PolicyEffect.ASK) {
            if (!approved) throw new SecurityException("POLICY_CHALLENGE_UNSATISFIED");
            var now = time.now();
            policy.evidence()
                    .save(new PolicyAuthorizationEvidence(
                            value.decision().id(),
                            value.decision().requestDigest(),
                            new ApprovalRequester(tenant, principal),
                            new ApprovalResponder(tenant, principal),
                            now,
                            now.plus(Duration.ofMinutes(5))));
        }
        var result = operations.executeUserInitiated(
                value.auditRunId(),
                tenant,
                principal,
                new RunWorkspaceAccess(workspaceId, Set.of("execution.run")),
                value.command(),
                ".",
                timeout,
                "terminal-shell-" + value.token(),
                value.policyDecisionRef());
        String status = String.valueOf(result.structuredData().getOrDefault("status", "UNKNOWN"));
        Optional<Integer> exitCode = Optional.ofNullable(result.structuredData().get("exitCode"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue);
        Optional<String> outputRef = Optional.ofNullable(result.structuredData().get("outputRef"))
                .map(String::valueOf)
                .filter(text -> !text.isBlank());
        String contextText = result.summary()
                + outputRef.map(reference -> "\noutput-ref: " + reference).orElse("");
        persistence.unitOfWork().execute(() -> {
            persistence
                    .state()
                    .appendSessionMessage(new io.haifa.agent.runtime.core.storage.SessionMessageDraft(
                            new AgentMessageId(ids.nextValue()),
                            value.sessionId(),
                            Optional.empty(),
                            Optional.empty(),
                            MessageRole.RUNTIME,
                            MessageStatus.COMPLETED,
                            value.includeInContext() ? MessageVisibility.AGENT_VISIBLE : MessageVisibility.INTERNAL,
                            List.of(new TextPart(contextText, "text/plain")),
                            Map.of(
                                    "origin",
                                    "terminal-shell",
                                    "status",
                                    status,
                                    "includedInContext",
                                    value.includeInContext()),
                            time.now()));
            return null;
        });
        return new CodingShellResult(
                status, exitCode, result.summary(), outputRef, result.truncated(), value.includeInContext());
    }

    @Override
    public void discard(String token) {
        pending.remove(token);
    }

    private static String command(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("shell command must not be blank");
        if (value.length() > 4_096 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("shell command is invalid");
        }
        return value.strip();
    }

    private static void rejectUnsupportedMode(String command) {
        String lower = command.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches("(?s).*\\s&\\s*$") || lower.contains("start-process")) {
            throw new IllegalArgumentException("BACKGROUND_JOB_NOT_SUPPORTED");
        }
        if (lower.contains("read-host") || lower.matches("(?s).*(^|\\s)(vim|vi|nano|less|more|top|ssh)(\\s|$).*")) {
            throw new IllegalArgumentException("PTY_NOT_SUPPORTED");
        }
    }

    private record Pending(
            String token,
            AgentSessionId sessionId,
            AgentRunId auditRunId,
            String command,
            boolean includeInContext,
            String policyDecisionRef,
            io.haifa.agent.policy.api.PolicyDecision decision) {}
}
