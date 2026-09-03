package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.git.GitRepositoryRef;
import io.haifa.agent.project.hostworkspace.HostGitInspectionPort;
import io.haifa.agent.project.hostworkspace.HostGitInspectionUnavailableException;
import io.haifa.agent.project.hostworkspace.HostRepositoryLocator;
import io.haifa.agent.project.hostworkspace.scope.ResolvedAuthorizedPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Run-partitioned baseline and routing registry; no global state or ThreadLocal is used. */
public final class RunRepositoryBaselineRegistry {
    private final Function<RepositoryRunContext, HostGitInspectionPort> inspections;
    private final RepositoryBaselineCapture capture;
    private final ConcurrentHashMap<String, RunState> runs = new ConcurrentHashMap<>();

    public RunRepositoryBaselineRegistry(HostGitInspectionPort inspection, RepositoryBaselineCapture capture) {
        this(ignored -> inspection, capture);
    }

    public RunRepositoryBaselineRegistry(
            Function<RepositoryRunContext, HostGitInspectionPort> inspections, RepositoryBaselineCapture capture) {
        this.inspections = Objects.requireNonNull(inspections, "inspections must not be null");
        this.capture = Objects.requireNonNull(capture, "capture must not be null");
    }

    public void beforeManagedWrite(RepositoryRunContext context, ResolvedAuthorizedPath target) {
        Objects.requireNonNull(context, "context must not be null");
        String run = context.runRef();
        Objects.requireNonNull(target, "target must not be null");
        RunState state = state(context);
        final java.util.Optional<io.haifa.agent.project.hostworkspace.LocatedRepository> located;
        try {
            located = state.repositories.locate(target);
        } catch (HostGitInspectionUnavailableException exception) {
            state.partial = true;
            return;
        }
        if (located.isEmpty()) {
            state.targets.put(
                    target.workspacePath(),
                    new PlainReviewTarget(target.directory().workspaceId()));
            return;
        }
        GitRepositoryRef repository = new GitRepositoryRef(located.orElseThrow().workspaceRoot());
        final RepositoryBaseline baseline;
        try {
            baseline = state.baselines.computeIfAbsent(repository, ignored -> capture.capture(context, repository));
        } catch (RuntimeException exception) {
            throw new RepositoryBaselineUnavailableException("repository baseline is unavailable", exception);
        }
        state.targets.put(target.workspacePath(), new GitReviewTarget(repository));
        if (baseline.attributionStatus() == AttributionStatus.ATTRIBUTION_PARTIAL) {
            state.partial = true;
        }
    }

    public void beforeExecution(RepositoryRunContext context, ResolvedAuthorizedPath workdir) {
        beforeManagedWrite(context, workdir);
        markPartial(context);
    }

    public void afterExecution(RepositoryRunContext context, WorkspaceId workspaceId) {
        RunState state = state(Objects.requireNonNull(context, "context must not be null"));
        state.repositories.invalidate(Objects.requireNonNull(workspaceId, "workspaceId must not be null"));
        markPartial(context);
    }

    public void markPartial(RepositoryRunContext context) {
        state(Objects.requireNonNull(context, "context must not be null")).partial = true;
    }

    public List<RepositoryBaseline> baselines(String runRef) {
        RunState state = runs.get(runRef(runRef));
        return state == null ? List.of() : List.copyOf(state.baselines.values());
    }

    public Map<WorkspacePath, ReviewTarget> targetAssignments(String runRef) {
        RunState state = runs.get(runRef(runRef));
        return state == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(state.targets));
    }

    public AttributionStatus attributionStatus(String runRef) {
        RunState state = runs.get(runRef(runRef));
        return state != null && state.partial ? AttributionStatus.ATTRIBUTION_PARTIAL : AttributionStatus.COMPLETE;
    }

    public RepositoryRunContext context(String runRef) {
        RunState state = runs.get(runRef(runRef));
        if (state == null) throw new IllegalStateException("repository review run is unavailable");
        return state.context;
    }

    private RunState state(RepositoryRunContext context) {
        RunState state = runs.computeIfAbsent(
                context.runRef(),
                ignored -> new RunState(context, new HostRepositoryLocator(inspections.apply(context))));
        if (!state.context.equals(context)) {
            throw new SecurityException("repository review context changed for an existing run");
        }
        return state;
    }

    private static String runRef(String value) {
        String normalized =
                Objects.requireNonNull(value, "runRef must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("runRef must not be blank");
        return normalized;
    }

    private static final class RunState {
        private final RepositoryRunContext context;
        private final HostRepositoryLocator repositories;
        private final ConcurrentHashMap<GitRepositoryRef, RepositoryBaseline> baselines = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<WorkspacePath, ReviewTarget> targets = new ConcurrentHashMap<>();
        private volatile boolean partial;

        private RunState(RepositoryRunContext context, HostRepositoryLocator repositories) {
            this.context = Objects.requireNonNull(context, "context must not be null");
            this.repositories = Objects.requireNonNull(repositories, "repositories must not be null");
        }
    }
}
