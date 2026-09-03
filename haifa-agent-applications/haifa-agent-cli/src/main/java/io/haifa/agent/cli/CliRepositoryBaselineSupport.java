package io.haifa.agent.cli;

import io.haifa.agent.application.project.policy.CodingAgentPolicyAssembly;
import io.haifa.agent.application.project.product.coding.delivery.AttributionStatus;
import io.haifa.agent.application.project.product.coding.delivery.RepositoryBaseline;
import io.haifa.agent.application.project.product.coding.delivery.RepositoryReviewCapture;
import io.haifa.agent.application.project.product.coding.delivery.RepositoryRunContext;
import io.haifa.agent.application.project.product.coding.delivery.RunRepositoryBaselineRegistry;
import io.haifa.agent.application.project.tool.ExecutionRepositoryBaselineObserver;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.execution.core.ExecutionPolicyBinding;
import io.haifa.agent.git.ExecutionBrokerGitReviewProbe;
import io.haifa.agent.git.ExecutionBrokerHostGitInspectionPort;
import io.haifa.agent.git.GitCommandContext;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicySubject;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.hostworkspace.scope.ResolvedAuthorizedPath;
import io.haifa.agent.project.path.WorkspacePath;
import java.util.Optional;
import java.util.Set;

/** CLI wiring for run-scoped repository baselines over the existing ExecutionBroker. */
final class CliRepositoryBaselineSupport {
    // This fixed, read-only adapter is not a model-requested shell action and must not create a second approval prompt.
    private static final String INTERNAL_GIT_PRODUCT = "haifa-coding-agent-internal-git";
    private static final Set<String> CAPABILITIES = Set.of("execution.run", "git.read");

    private final RunRepositoryBaselineRegistry registry;
    private final ExecutionRepositoryBaselineObserver observer;
    private final RepositoryReviewCapture reviews;

    private CliRepositoryBaselineSupport(
            RunRepositoryBaselineRegistry registry,
            ExecutionRepositoryBaselineObserver observer,
            RepositoryReviewCapture reviews) {
        this.registry = registry;
        this.observer = observer;
        this.reviews = reviews;
    }

    static CliRepositoryBaselineSupport create(
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            SandboxProfileRef profile,
            CodingAgentPolicyAssembly policy,
            AuthorizedWorkspaceProvisioning provisioning) {
        var review = new ExecutionBrokerGitReviewProbe(broker, identifiers, profile, "git");
        RunRepositoryBaselineRegistry registry = new RunRepositoryBaselineRegistry(
                context -> new ExecutionBrokerHostGitInspectionPort(
                        broker, identifiers, profile, "git", commandContext(context, policy)),
                (context, repository) -> {
                    var snapshot = review.captureBaseline(commandContext(context, policy), repository);
                    return new RepositoryBaseline(
                            repository,
                            snapshot.headRevision(),
                            snapshot.dirtySnapshotDigest(),
                            snapshot.complete() ? AttributionStatus.COMPLETE : AttributionStatus.ATTRIBUTION_PARTIAL);
                });
        ExecutionRepositoryBaselineObserver observer = new ExecutionRepositoryBaselineObserver() {
            @Override
            public void beforeDispatch(TenantRef tenant, String runRef, PrincipalRef actor, WorkspacePath workdir) {
                registry.beforeExecution(
                        new RepositoryRunContext(tenant, runRef, actor), resolve(provisioning, workdir));
            }

            @Override
            public void afterCompletion(TenantRef tenant, String runRef, PrincipalRef actor, WorkspacePath workdir) {
                registry.afterExecution(new RepositoryRunContext(tenant, runRef, actor), workdir.workspaceId());
            }
        };
        RepositoryReviewCapture reviews = (runRef, baseline) -> review.review(
                commandContext(registry.context(runRef), policy),
                baseline.repository(),
                baseline.dirtySnapshotDigest());
        return new CliRepositoryBaselineSupport(registry, observer, reviews);
    }

    RunRepositoryBaselineRegistry registry() {
        return registry;
    }

    ExecutionRepositoryBaselineObserver observer() {
        return observer;
    }

    RepositoryReviewCapture reviews() {
        return reviews;
    }

    private static GitCommandContext commandContext(RepositoryRunContext context, CodingAgentPolicyAssembly policy) {
        TrustedExecutionContext execution = new TrustedExecutionContext(
                context.tenant(), context.runRef(), context.actor(), CAPABILITIES, "internal-git-read-pending");
        return new GitCommandContext(execution, request -> authorize(policy, request));
    }

    static String authorize(CodingAgentPolicyAssembly policy, ExecutionRequest request) {
        PolicyRequest policyRequest = new PolicyRequest(
                new PolicySubject(request.context().tenant(), request.context().actor(), INTERNAL_GIT_PRODUCT),
                PolicyContext.run(request.context().runRef(), policy.snapshot().approvalMode()),
                new PolicyAction("execution.run", "invoke"),
                new PolicyResource(
                        "execution",
                        "internal-git-read-" + request.id().value(),
                        Optional.of(ExecutionPolicyBinding.resourceDigest(request)),
                        "Bounded internal Git review read"),
                new PolicyRisk(PolicyRiskLevel.LOW, Set.of(), false, Optional.empty()));
        var decision = policy.decisions().evaluate(policyRequest, policy.snapshot());
        policy.decisionsStore().save(decision);
        return decision.id().value();
    }

    private static ResolvedAuthorizedPath resolve(AuthorizedWorkspaceProvisioning provisioning, WorkspacePath workdir) {
        AuthorizedHostDirectory directory = provisioning.scope().allowedDirectories().stream()
                .filter(candidate -> candidate.workspaceId().equals(workdir.workspaceId()))
                .findFirst()
                .orElseThrow(() -> new SecurityException("execution workspace is not currently authorized"));
        return provisioning
                .scope()
                .resolve(directory
                        .realPath()
                        .resolve(workdir.projectPath().value())
                        .normalize()
                        .toString());
    }
}
