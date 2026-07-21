package io.haifa.agent.sandbox.host;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.sandbox.api.GitWorktreeIsolationProvider;
import io.haifa.agent.sandbox.api.GitWorktreeRequest;
import io.haifa.agent.sandbox.api.IsolatedWorkspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class HostGitWorktreeIsolationProvider implements GitWorktreeIsolationProvider {
    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final LocalWorkspaceLocationStore locations;
    private final Path controlledBase;
    private final String gitExecutable;
    private final TimeProvider time;
    private final ConcurrentHashMap<WorkspaceId, OwnedWorktree> owned = new ConcurrentHashMap<>();

    public HostGitWorktreeIsolationProvider(
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            LocalWorkspaceLocationStore locations,
            Path controlledBase,
            String gitExecutable,
            TimeProvider time) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.controlledBase = Objects.requireNonNull(controlledBase, "controlledBase must not be null")
                .toAbsolutePath()
                .normalize();
        this.gitExecutable = Objects.requireNonNull(gitExecutable, "gitExecutable must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    @Override
    public IsolatedWorkspace createWorktree(GitWorktreeRequest request) {
        Workspace parent =
                workspaces.find(request.parentWorkspaceId()).orElseThrow(() -> failure("parent workspace not found"));
        var parentBinding =
                bindings.find(parent.root().bindingId()).orElseThrow(() -> failure("parent binding not found"));
        if (!parentBinding
                        .capabilities()
                        .values()
                        .containsAll(request.narrowedCapabilities().values())
                || !parentBinding
                        .permissions()
                        .values()
                        .containsAll(request.narrowedPermissions().values())) {
            throw failure("child worktree authority must be narrowed from parent");
        }
        try {
            Files.createDirectories(controlledBase);
            Path base = controlledBase.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path repository = locations
                    .resolveForTrustedProvider(parentBinding.locationRef())
                    .toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!run(repository, List.of("rev-parse", "--is-inside-work-tree"), Duration.ofSeconds(5))
                    .trim()
                    .equals("true")) {
                throw new UnsupportedOperationException("COPY_ON_WRITE requires a Git repository");
            }
            run(repository, List.of("cat-file", "-e", request.baseCommit() + "^{commit}"), Duration.ofSeconds(5));
            Path target = base.resolve(
                            "worktree-" + safeName(request.childWorkspaceId().value()))
                    .normalize();
            if (!target.startsWith(base) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("worktree target is unavailable");
            }
            run(
                    repository,
                    List.of(
                            "-c",
                            "credential.interactive=never",
                            "worktree",
                            "add",
                            "--detach",
                            target.toString(),
                            request.baseCommit()),
                    Duration.ofSeconds(30));
            locations.register(request.childLocationRef(), target);
            Instant now = time.now();
            WorkspaceBinding binding = WorkspaceBinding.provision(
                            request.childBindingId(),
                            request.childLocationRef(),
                            WorkspaceBindingMode.COPY_ON_WRITE,
                            request.owner(),
                            request.narrowedCapabilities(),
                            request.narrowedPermissions(),
                            LocalWorkspaceLocationStore.fingerprintFor(target),
                            now)
                    .activate(now);
            bindings.create(binding);
            Workspace child = Workspace.provision(
                            request.childWorkspaceId(),
                            parent.projectId(),
                            WorkspacePurpose.CHILD,
                            new WorkspaceRoot(
                                    ProjectPath.root(),
                                    binding.id(),
                                    parent.root().semanticsId()),
                            WorkspaceRevision.initial("git:" + request.baseCommit()),
                            now)
                    .activate(now);
            workspaces.create(child);
            owned.put(
                    child.id(),
                    new OwnedWorktree(
                            repository, target, request.childLocationRef(), binding.id(), request.baseCommit()));
            return new IsolatedWorkspace(
                    parent.id(),
                    child.id(),
                    binding.id(),
                    binding.locationRef(),
                    WorkspaceBindingMode.COPY_ON_WRITE,
                    parent.revision(),
                    now);
        } catch (IOException exception) {
            throw failure("worktree storage is unavailable");
        }
    }

    @Override
    public void releaseWorktree(WorkspaceId childWorkspaceId, boolean confirmedDiscard) {
        OwnedWorktree worktree = owned.get(childWorkspaceId);
        if (worktree == null) throw failure("worktree is not owned by this provider");
        String dirty = run(
                worktree.target(),
                List.of("status", "--porcelain=v1", "--untracked-files=all"),
                Duration.ofSeconds(10));
        if (!dirty.isBlank() && !confirmedDiscard) throw failure("worktree has unconfirmed changes");
        Workspace workspace = workspaces.find(childWorkspaceId).orElseThrow(() -> failure("child workspace not found"));
        var binding = bindings.find(worktree.bindingId()).orElseThrow(() -> failure("child binding not found"));
        Instant now = time.now();
        Workspace releasingWorkspace = workspace.beginRelease(now);
        var releasingBinding = binding.beginRelease(now);
        workspaces.save(releasingWorkspace, workspace.version());
        bindings.save(releasingBinding, binding.version());
        List<String> removeArguments = confirmedDiscard
                ? List.of(
                        "-c",
                        "credential.interactive=never",
                        "worktree",
                        "remove",
                        "--force",
                        worktree.target().toString())
                : List.of(
                        "-c",
                        "credential.interactive=never",
                        "worktree",
                        "remove",
                        worktree.target().toString());
        run(worktree.repository(), removeArguments, Duration.ofSeconds(30));
        safeDeleteIfPresent(worktree.target());
        locations.unregisterForTrustedProvider(worktree.locationRef(), worktree.target());
        bindings.save(releasingBinding.released(now), releasingBinding.version());
        workspaces.save(releasingWorkspace.released(now), releasingWorkspace.version());
        owned.remove(childWorkspaceId, worktree);
    }

    private String run(Path cwd, List<String> arguments, Duration timeout) {
        try {
            java.util.ArrayList<String> command = new java.util.ArrayList<>();
            command.add(gitExecutable);
            command.addAll(arguments);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(cwd.toFile());
            builder.environment().clear();
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            process.getOutputStream().close();
            byte[] output = process.getInputStream().readNBytes(64 * 1024);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw failure("git command timed out");
            }
            if (process.exitValue() != 0) throw failure("git command failed without credential interaction");
            return new String(output, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw failure("git command is unavailable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("git command was interrupted");
        }
    }

    private void safeDeleteIfPresent(Path target) {
        Path base = controlledBase.toAbsolutePath().normalize();
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(base) || normalized.equals(base)) throw failure("worktree cleanup target is unsafe");
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw failure("worktree cleanup requires reconciliation");
        }
    }

    private static String safeName(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static HostSandboxException failure(String message) {
        return new HostSandboxException("GIT_WORKTREE_FAILURE", message);
    }

    private record OwnedWorktree(
            Path repository,
            Path target,
            io.haifa.agent.project.binding.WorkspaceLocationRef locationRef,
            io.haifa.agent.project.binding.WorkspaceBindingId bindingId,
            String baseCommit) {}
}
