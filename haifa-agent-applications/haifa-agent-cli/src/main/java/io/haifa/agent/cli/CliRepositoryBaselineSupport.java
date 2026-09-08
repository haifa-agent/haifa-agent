package io.haifa.agent.cli;

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
import io.haifa.agent.execution.api.ExecutionOrigin;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.git.ExecutionBrokerGitReviewProbe;
import io.haifa.agent.git.ExecutionBrokerHostGitInspectionPort;
import io.haifa.agent.git.GitCommandContext;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.hostworkspace.scope.ResolvedAuthorizedPath;
import io.haifa.agent.project.path.WorkspacePath;
import java.util.Set;

/** CLI wiring for run-scoped repository baselines over the existing ExecutionBroker. */
final class CliRepositoryBaselineSupport {
    // This fixed, read-only adapter is not a model-requested shell action and must not create a second approval prompt.
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
            AuthorizedWorkspaceProvisioning provisioning) {
        var review = new ExecutionBrokerGitReviewProbe(broker, identifiers, profile, "git");
        RunRepositoryBaselineRegistry registry = new RunRepositoryBaselineRegistry(
                context -> new ExecutionBrokerHostGitInspectionPort(
                        broker, identifiers, profile, "git", commandContext(context)),
                (context, repository) -> {
                    var snapshot = review.captureBaseline(commandContext(context), repository);
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
                commandContext(registry.context(runRef)), baseline.repository(), baseline.dirtySnapshotDigest());
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

    private static GitCommandContext commandContext(RepositoryRunContext context) {
        TrustedExecutionContext execution = new TrustedExecutionContext(
                context.tenant(),
                context.runRef(),
                context.actor(),
                CAPABILITIES,
                ExecutionOrigin.PRODUCT_INTERNAL,
                java.util.Optional.empty());
        return new GitCommandContext(execution);
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
